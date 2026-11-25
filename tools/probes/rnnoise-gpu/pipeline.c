// Frame analysis and synthesis adapted from RNNoise v0.1. See LICENSE.rnnoise.
#include <stdio.h>
#include <stdlib.h>
#include "rnn.h"

static FILE *trace;

static void reference_prediction(RNNState *state, float *gains, float *vad, const float *features) {
    compute_rnn(state, gains, vad, features);
    if (trace && (fwrite(gains, sizeof(float), 22, trace) != 22 || fwrite(vad, sizeof(float), 1, trace) != 1)) {
        perror("write CPU predictions");
        exit(9);
    }
}

#define compute_rnn reference_prediction
#include "denoise.c"
#undef compute_rnn
#include <stdint.h>
#include <time.h>

enum { BATCH = 12, OUTPUTS = NB_BANDS + 1 };
typedef struct {
    kiss_fft_cpx spectrum[FREQ_SIZE], pitch[WINDOW_SIZE];
    float energy[NB_BANDS], pitch_energy[NB_BANDS], cross[NB_BANDS];
    float features[NB_FEATURES], scores[OUTPUTS];
    int silence;
} Frame;

static Frame frames[BATCH];

static void analyze(DenoiseState *state, Frame *f, const float *input) {
    static const float a[2] = {-1.99599, 0.99600}, b[2] = {-2, 1};
    float filtered[FRAME_SIZE];
    biquad(filtered, state->mem_hp_x, input, b, a, FRAME_SIZE);
    f->silence = compute_frame_features(state, f->spectrum, f->pitch, f->energy,
                                        f->pitch_energy, f->cross, f->features, filtered);
}

static void synthesize(DenoiseState *state, Frame *f, float *output) {
    if (!f->silence) {
        float gain[FREQ_SIZE] = {1};
        pitch_filter(f->spectrum, f->pitch, f->energy, f->pitch_energy, f->cross, f->scores);
        interp_band_gain(gain, f->scores);
        for (int i = 0; i < FREQ_SIZE; ++i) {
            f->spectrum[i].r *= gain[i];
            f->spectrum[i].i *= gain[i];
        }
    }
    frame_synthesis(state, output, f->spectrum);
}

static double seconds(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec * 1e-9;
}

// CPU mode calls the unmodified upstream. GPU mode exchanges only active frames
// with the parent via pipes. Keep stdout exclusively for the binary protocol.
int main(int argc, char **argv) {
    if ((argc != 4 && argc != 5) || (strcmp(argv[1], "gpu") && strcmp(argv[1], "cpu"))) return 2;
    if (argc == 5 && !(trace = fopen(argv[4], "wb"))) return 3;
    int gpu = !strcmp(argv[1], "gpu");
    FILE *in = fopen(argv[2], "rb"), *out = fopen(argv[3], "wb");
    DenoiseState *state = rnnoise_create();
    if (!in || !out || !state) return 3;
    int total = 0;
    double start = seconds();
    for (;;) {
        int n = 0, active = 0;
        float audio[BATCH][FRAME_SIZE];
        for (; n < BATCH; ++n) {
            int16_t raw[FRAME_SIZE];
            size_t read = fread(raw, sizeof(*raw), FRAME_SIZE, in);
            if (!read && feof(in)) break;
            if (read != FRAME_SIZE) return 4;
            for (int i = 0; i < FRAME_SIZE; ++i) audio[n][i] = raw[i];
            if (gpu) {
                analyze(state, &frames[n], audio[n]);
                active += !frames[n].silence;
            } else rnnoise_process_frame(state, audio[n], audio[n]);
        }
        if (!n) break;
        if (gpu && active) {
            if (putchar(active) == EOF) return 5;
            for (int i = 0; i < n; ++i)
                if (!frames[i].silence && fwrite(frames[i].features, sizeof(float), NB_FEATURES, stdout) != NB_FEATURES) return 5;
            if (fflush(stdout)) return 5;
            for (int i = 0; i < n; ++i)
                if (!frames[i].silence && fread(frames[i].scores, sizeof(float), OUTPUTS, stdin) != OUTPUTS) return 6;
        }
        for (int i = 0; i < n; ++i) {
            if (gpu) {
                if (trace && !frames[i].silence && fwrite(frames[i].scores, sizeof(float), OUTPUTS, trace) != OUTPUTS) return 9;
                synthesize(state, &frames[i], audio[i]);
            }
            if (fwrite(audio[i], sizeof(float), FRAME_SIZE, out) != FRAME_SIZE) return 7;
        }
        total += n;
    }
    if ((trace && fclose(trace)) || fclose(out) || !total) return 8;
    fprintf(stderr, "%s frames=%d pipeline_ms=%.2f\n", argv[1], total, (seconds()-start)*1000);
    fclose(in);
    rnnoise_destroy(state);
    return 0;
}
