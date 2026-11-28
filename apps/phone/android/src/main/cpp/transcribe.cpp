// A private, persistent worker. Keep weights resident, but reset the model context for each window.
#include "llama.h"
#include "ggml-backend.h"
#include "mtmd-helper.h"
#include <arpa/inet.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
#include <android/log.h>
#include <stdexcept>
#include <memory>
#include <unistd.h>

static void require_ok(bool ok, const char *message) {
    if (!ok) throw std::runtime_error(message);
}

static ggml_backend_dev_t devices[2] = {};
static std::atomic_bool assigned_layer{false};
static std::atomic_bool offloaded_layer{false};

// b12830 reports placement during model loading, including layers it could only put on the CPU.
static void placement_log(ggml_log_level level, const char *text, void *device) {
    const std::string line(text);
    if (line.find("assigned to device " + std::string(static_cast<const char *>(device)) + ",") != std::string::npos)
        assigned_layer = true;
    auto offload = line.find("offloaded ");
    if (offload != std::string::npos && std::atoi(text + offload + 10) > 0) offloaded_layer = true;
    if (level == GGML_LOG_LEVEL_ERROR || offload != std::string::npos || line.find("model buffer size") != std::string::npos)
        fputs(text, stderr);
}
static void select_layers(llama_model_params &params) {
    params.devices = devices;
    params.n_gpu_layers = devices[0] ? 99 : 0;
}

// Reuse the upstream SANM encoder, adaptor, prompt and greedy decoder on Android.
#define main funasr_cli_main
#include "funasr-cli.cpp"
#undef main

static std::string qwen_window(asr_decoder &decoder, mtmd_context *audio, const std::vector<float> &pcm) {
    llama_memory_clear(llama_get_memory(decoder.ctx), true);
    llama_sampler_reset(decoder.smpl);
    static const char prompt[] = "<|im_start|>user\n<__media__><|im_end|>\n<|im_start|>assistant\n";
    const mtmd_input_text text{prompt, sizeof(prompt) - 1, true, true};
    std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmap(
        mtmd_bitmap_init_from_audio(pcm.size(), pcm.data()), mtmd_bitmap_free);
    std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
    const mtmd_bitmap *input = bitmap.get();
    require_ok(input && chunks && mtmd_tokenize(audio, chunks.get(), &text, &input, 1) == 0, "audio tokenization");
    int past = 0;
    require_ok(mtmd_helper_eval_chunks(audio, decoder.ctx, chunks.get(), 0, 0, 256, true, &past) == 0, "audio encoding");
    std::string out;
    for (int i = 0; i < 256; ++i) {
        llama_token token = llama_sampler_sample(decoder.smpl, decoder.ctx, -1);
        if (llama_vocab_is_eog(decoder.vocab, token)) return out;
        char piece[512];
        int size = llama_token_to_piece(decoder.vocab, token, piece, sizeof(piece), 0, true);
        require_ok(size >= 0, "invalid token");
        out.append(piece, size);
        decode_batch(decoder.ctx, 1, &token, nullptr, 0, past, true);
    }
    throw std::runtime_error("output limit");
}

static void reply(FILE *stream, const std::string &text) {
    uint32_t size = htonl(static_cast<uint32_t>(text.size()));
    require_ok(fwrite(&size, sizeof(size), 1, stream) == 1 &&
               fwrite(text.data(), 1, text.size(), stream) == text.size() && fflush(stream) == 0, "output closed");
}

int main(int argc, char **argv) {
    if (argc != 5) return 2;
    FILE *wire = fdopen(dup(STDOUT_FILENO), "wb");
    if (!wire || dup2(STDERR_FILENO, STDOUT_FILENO) < 0) return 2;
    asr_decoder decoder{};
    mtmd_context *audio = nullptr;
    int status = 0;
    try {
        llama_log_set(placement_log, argv[2]);
        ggml_backend_load_all();
        if (strcmp(argv[2], "CPU") != 0) {
            devices[0] = ggml_backend_dev_by_name(argv[2]);
            require_ok(devices[0], "requested processor unavailable");
        }
        bool fun = strcmp(argv[1], "fun") == 0;
        int rate = 16000;
        if (fun) {
            require_ok(load_decoder(argv[3], argv[4], 1.0f, "", decoder), "Fun-ASR model initialization");
        } else {
            require_ok(strcmp(argv[1], "qwen") == 0, "unknown model family");
            auto params = llama_model_default_params();
            select_layers(params);
            decoder.model = llama_model_load_from_file(argv[4], params);
            require_ok(decoder.model, "Qwen model initialization");
            auto context = llama_context_default_params();
            context.n_ctx = 2048; context.n_batch = 256; context.n_ubatch = 128;
            context.n_threads = 4; context.n_threads_batch = 4;
            decoder.ctx = llama_init_from_model(decoder.model, context);
            decoder.vocab = llama_model_get_vocab(decoder.model);
            decoder.smpl = llama_sampler_init_greedy();
            auto projector = mtmd_context_params_default();
            projector.use_gpu = false;
            projector.n_threads = 4;
            audio = mtmd_init_from_file(argv[3], decoder.model, projector);
            require_ok(decoder.ctx && decoder.smpl && audio && mtmd_support_audio(audio), "Qwen audio initialization");
            rate = mtmd_get_audio_sample_rate(audio);
            require_ok(rate == 16000, "unexpected model sample rate");
        }
        require_ok(!devices[0] || (assigned_layer && offloaded_layer), "no decoder layers on requested processor");
        __android_log_print(ANDROID_LOG_INFO, "KoeMemoASR", "family=%s decoder=%s encoder=CPU", argv[1], argv[2]);
        reply(wire, "ready");
        uint32_t header[2];
        while (fread(header, sizeof(header), 1, stdin) == 1) {
            uint32_t input_rate = ntohl(header[0]), count = ntohl(header[1]);
            require_ok(input_rate >= 8000 && input_rate <= 96000 && count > 0 && count <= input_rate * 15, "invalid PCM window");
            std::vector<float> pcm(count);
            require_ok(fread(pcm.data(), sizeof(float), count, stdin) == count, "incomplete PCM");
            if (input_rate != static_cast<unsigned>(rate)) {
                std::vector<float> resampled((static_cast<uint64_t>(count) * rate + input_rate - 1) / input_rate);
                auto n = ma_convert_frames(resampled.data(), resampled.size(), ma_format_f32, 1, rate,
                                           pcm.data(), count, ma_format_f32, 1, input_rate);
                require_ok(n > 0, "resampling failed");
                resampled.resize(n);
                pcm.swap(resampled);
            }
            reply(wire, pcm.size() < WINLEN ? "" : fun ? asr_decode_window(decoder, pcm.data(), pcm.size(), 256)
                                                       : qwen_window(decoder, audio, pcm));
        }
    } catch (const std::exception &e) {
        fprintf(stderr, "KoeMemo ASR: %s\n", e.what());
        status = 1;
    }
    if (audio) mtmd_free(audio);
    free_decoder(decoder);
    fclose(wire);
    return status;
}
