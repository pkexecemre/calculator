// Minimal JNI bridge: load a ggml model once, transcribe 16 kHz mono float PCM.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define LOG_TAG "LocalSTT"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static whisper_context *g_ctx = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_simplecalculator_app_data_stt_WhisperBridge_nativeInit(
        JNIEnv *env, jobject, jstring modelPath) {
    if (g_ctx != nullptr) return JNI_TRUE;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    g_ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);
    if (g_ctx == nullptr) {
        LOGE("failed to load model");
        return JNI_FALSE;
    }
    LOGI("model loaded");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_simplecalculator_app_data_stt_WhisperBridge_nativeFree(JNIEnv *, jobject) {
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}

/** Returns newline separated "startMs|endMs|text" for one chunk. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_simplecalculator_app_data_stt_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jobject, jfloatArray pcm, jint length, jboolean translateToEnglish,
        jstring language, jint threads) {
    if (g_ctx == nullptr) return env->NewStringUTF("");

    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = translateToEnglish == JNI_TRUE;
    if (language != nullptr) {
        const char *lang = env->GetStringUTFChars(language, nullptr);
        params.language = lang;
    }
    params.single_segment = false;
    params.no_context = true;
    params.suppress_blank = true;
    params.n_threads = threads > 0 ? threads : 4;

    std::string out;
    if (whisper_full(g_ctx, params, samples, length) == 0) {
        const int segments = whisper_full_n_segments(g_ctx);
        for (int i = 0; i < segments; i++) {
            const char *text = whisper_full_get_segment_text(g_ctx, i);
            const int64_t start = whisper_full_get_segment_t0(g_ctx, i) * 10; // centiseconds -> ms
            const int64_t end = whisper_full_get_segment_t1(g_ctx, i) * 10;
            if (text == nullptr) continue;
            out += std::to_string(start);
            out += "|";
            out += std::to_string(end);
            out += "|";
            out += text;
            out += "\n";
        }
    } else {
        LOGE("whisper_full failed");
    }
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    return env->NewStringUTF(out.c_str());
}
