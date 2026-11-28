#include <jni.h>
#include <exception>
#include "funasr_vad.h"

// The APK and the upstream Fun-ASR GGUF CLI call the same offline FSMN segmentation code.
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_koememo_phone_FunVad_detect(JNIEnv *env, jobject, jstring model, jfloatArray samples) {
    try {
        const jsize count = env->GetArrayLength(samples);
        if (count < 400) return env->NewIntArray(0);
        std::vector<float> pcm(count);
        env->GetFloatArrayRegion(samples, 0, count, pcm.data());
        if (env->ExceptionCheck()) return nullptr;
        const char *path = env->GetStringUTFChars(model, nullptr);
        if (!path) return nullptr;
        std::string filename(path);
        env->ReleaseStringUTFChars(model, path);
        std::vector<std::pair<int, int>> speech;
        if (!funasr_vad_segments(filename, pcm, 30000, speech, 2)) {
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "FunASR FSMN-VAD inference failed");
            return nullptr;
        }
        std::vector<jint> times;
        for (const auto &span : speech) {
            times.push_back(span.first);
            times.push_back(span.second);
        }
        jintArray result = env->NewIntArray(static_cast<jsize>(times.size()));
        if (result && !times.empty()) env->SetIntArrayRegion(result, 0, times.size(), times.data());
        return result;
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}
