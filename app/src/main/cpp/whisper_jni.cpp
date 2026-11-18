// JNI bridge over whisper.cpp for short live-caption windows.
#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <cstdint>
#include <string>

#include "whisper.h"

#define LOG_TAG "LocalSTT"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static whisper_context *g_ctx = nullptr;
static std::string g_model_path;

static std::string from_java(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_simplecalculator_app_data_stt_WhisperBridge_nativeInit(
        JNIEnv *env, jobject, jstring modelPath) {
    const std::string path = from_java(env, modelPath);
    if (path.empty()) return JNI_FALSE;
    if (g_ctx != nullptr && g_model_path == path) return JNI_TRUE;
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_model_path.clear();
    }

    const int64_t started = now_ms();
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    g_ctx = whisper_init_from_file_with_params(path.c_str(), params);
    if (g_ctx == nullptr) {
        LOGE("failed to load model %s", path.c_str());
        return JNI_FALSE;
    }
    g_model_path = path;
    LOGI("model loaded in %lldms multilingual=%d n_audio_ctx=%d path=%s",
         (long long) (now_ms() - started),
         whisper_is_multilingual(g_ctx),
         whisper_n_audio_ctx(g_ctx),
         path.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_simplecalculator_app_data_stt_WhisperBridge_nativeFree(JNIEnv *, jobject) {
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_model_path.clear();
        LOGI("model freed");
    }
}

/**
 * Returns one "startMs\tendMs\tnoSpeechProb\ttext" line per segment for a
 * 16 kHz mono chunk. audioCtx shortens the encoder context for short windows;
 * it is raised here if the audio would not fit.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_simplecalculator_app_data_stt_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jobject, jfloatArray pcm, jint length, jboolean translateToEnglish,
        jstring language, jint threads, jstring initialPrompt, jint audioCtx) {
    if (g_ctx == nullptr || length <= 0) return env->NewStringUTF("");

    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) return env->NewStringUTF("");

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = translateToEnglish == JNI_TRUE;
    params.single_segment = false;
    params.no_context = true;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.no_timestamps = false;
    params.n_threads = threads > 0 ? threads : 4;
    // A 5 s window never needs this many tokens. The cap bounds the worst
    // case when the decoder misses EOT and starts repeating itself. Retrying
    // at higher temperatures does not break those loops, it only delays the
    // next caption, so decoding stays greedy.
    params.max_tokens = 48;
    params.temperature_inc = 0.0f;

    std::string language_value;
    if (language != nullptr) {
        language_value = from_java(env, language);
        if (!language_value.empty() && language_value != "auto") {
            params.language = language_value.c_str();
        } else {
            params.language = nullptr;
        }
    } else {
        params.language = nullptr; // auto-detect for the multilingual model
    }

    std::string prompt;
    if (initialPrompt != nullptr) {
        prompt = from_java(env, initialPrompt);
        if (!prompt.empty()) params.initial_prompt = prompt.c_str();
    }

    int ctx = audioCtx;
    const int max_ctx = whisper_n_audio_ctx(g_ctx);
    if (ctx <= 0) {
        // 100 mel frames per second, conv stride 2, plus a small margin.
        const int mel_frames = length / 160 + 1;
        ctx = 256;
        while (ctx < max_ctx && ctx * 2 < mel_frames + 4) ctx *= 2;
    }
    if (ctx > max_ctx) ctx = max_ctx;
    // Never truncate speech frames: the mel tensor is 2 * ctx frames wide.
    const int mel_frames = length / 160 + 1;
    while (ctx < max_ctx && ctx * 2 < mel_frames) ctx *= 2;
    params.audio_ctx = ctx;

    const int64_t started = now_ms();
    int segment_count = 0;
    std::string out;
    if (whisper_full(g_ctx, params, samples, length) == 0) {
        segment_count = whisper_full_n_segments(g_ctx);
        for (int i = 0; i < segment_count; i++) {
            const char *text = whisper_full_get_segment_text(g_ctx, i);
            if (text == nullptr) continue;
            std::string clean;
            for (const char *cursor = text; *cursor != '\0'; ++cursor) {
                const char value = *cursor;
                clean += (value == '\n' || value == '\r' || value == '\t') ? ' ' : value;
            }
            out += std::to_string(whisper_full_get_segment_t0(g_ctx, i) * 10);
            out += '\t';
            out += std::to_string(whisper_full_get_segment_t1(g_ctx, i) * 10);
            out += '\t';
            out += std::to_string(whisper_full_get_segment_no_speech_prob(g_ctx, i));
            out += '\t';
            out += clean;
            out += '\n';
        }
    } else {
        LOGE("whisper_full failed");
    }

    LOGI("infer %lldms audio=%.2fs ctx=%d threads=%d segments=%d",
         (long long) (now_ms() - started),
         length / 16000.0f,
         ctx,
         params.n_threads,
         segment_count);

    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    return env->NewStringUTF(out.c_str());
}
