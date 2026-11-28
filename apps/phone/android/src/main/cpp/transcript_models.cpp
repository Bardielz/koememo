// CPU worker for text generation and pooled document embeddings. Weights are loaded once per run.
#include "llama.h"
#include "ggml-backend.h"
#include <arpa/inet.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

static void check(bool ok, const char *message) {
    if (!ok) throw std::runtime_error(message);
}
static void reply(const void *data, size_t size) {
    uint32_t length = htonl(size);
    check(fwrite(&length, 4, 1, stdout) == 1 && fwrite(data, 1, size, stdout) == size && fflush(stdout) == 0, "write failed");
}

static void infer(llama_context *ctx, const llama_model *model, const std::string &input, bool embedding) {
    if (auto memory = llama_get_memory(ctx)) llama_memory_clear(memory, true);
    auto vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens(embedding ? 2048 : 3584);
    int n = llama_tokenize(vocab, input.data(), input.size(), tokens.data(), tokens.size(), true, !embedding);
    check(n > 0 && n <= static_cast<int>(tokens.size()), "input exceeds context");
    auto batch = llama_batch_get_one(tokens.data(), n);
    if (embedding) {
        int rc = llama_model_has_encoder(model) ? llama_encode(ctx, batch) : llama_decode(ctx, batch);
        check(rc == 0, "embedding failed");
        const float *vec = llama_get_embeddings_seq(ctx, 0);
        const int dim = llama_model_n_embd_out(model);
        check(vec && dim == 768, "unexpected embedding shape");
        double norm = 0;
        for (int i = 0; i < dim; ++i) norm += static_cast<double>(vec[i]) * vec[i];
        check(std::isfinite(norm) && norm > 0, "invalid embedding");
        std::vector<float> result(dim);
        for (int i = 0; i < dim; ++i) result[i] = vec[i] / std::sqrt(norm);
        reply(result.data(), result.size() * sizeof(float));
        return;
    }
    check(llama_decode(ctx, batch) == 0, "prefill failed");
    std::string output;
    for (int i = 0; i < 512; ++i) {
        const float *logits = llama_get_logits_ith(ctx, -1);
        check(logits, "missing logits");
        llama_token best = 0;
        for (int j = 1; j < llama_vocab_n_tokens(vocab); ++j) if (logits[j] > logits[best]) best = j;
        if (llama_vocab_is_eog(vocab, best)) { reply(output.data(), output.size()); return; }
        char piece[1024];
        auto len = llama_token_to_piece(vocab, best, piece, sizeof(piece), 0, true);
        check(len >= 0, "invalid token");
        output.append(piece, len);
        if (output == "NEXT" || output.size() >= 5 && output.compare(output.size()-5, 5, "\nNEXT") == 0) {
            reply(output.data(), output.size());
            return;
        }
        check(llama_decode(ctx, llama_batch_get_one(&best, 1)) == 0, "decode failed");
    }
    throw std::runtime_error("output limit");
}

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    bool embedding = strcmp(argv[1], "embed") == 0;
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int status = 0;
    try {
        const char *cpu = std::getenv("KOE_CPU_BACKEND");
        if (cpu) check(ggml_backend_load(cpu), "cannot load portable CPU backend");
        else ggml_backend_load_all();
        auto mp = llama_model_default_params();
        ggml_backend_dev_t devices[] = { nullptr };
        mp.devices = devices;
        mp.n_gpu_layers = 0;
        model = llama_model_load_from_file(argv[2], mp);
        check(model, "cannot load weights");
        auto cp = llama_context_default_params();
        cp.n_ctx = embedding ? 2048 : 4096;
        cp.n_batch = cp.n_ctx;
        cp.n_ubatch = embedding ? 2048 : 128;
        cp.embeddings = embedding;
        cp.n_threads = cp.n_threads_batch = 4;
        ctx = llama_init_from_model(model, cp);
        check(ctx, "cannot create context");
        reply("ready", 5);
        uint32_t size;
        while (fread(&size, 4, 1, stdin) == 1) {
            size = ntohl(size);
            check(size > 0 && size <= 65536, "invalid frame");
            std::string input(size, '\0');
            check(fread(input.data(), 1, size, stdin) == size, "truncated frame");
            infer(ctx, model, input, embedding);
        }
    } catch (const std::exception &err) {
        fprintf(stderr, "KoeMemo Gemma: %s\n", err.what());
        status = 1;
    }
    if (ctx) llama_free(ctx);
    if (model) llama_model_free(model);
    return status;
}
