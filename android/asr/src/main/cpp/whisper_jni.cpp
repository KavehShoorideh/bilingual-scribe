// JNI boundary for whisper.cpp.
//
// Design rules:
//  - No whisper types cross into Kotlin. Contexts and states are opaque longs.
//  - One context (model weights) may back several states (KV caches). That is
//    what makes a dual English/Farsi decode affordable: 1x weights + 2x state
//    instead of two whole models resident on a 6 GB phone.
//  - Word data is returned pre-packed as one tab-separated string per word
//    rather than five JNI calls per word.

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "bscribe-asr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

inline whisper_context *ctx_of(jlong h) { return reinterpret_cast<whisper_context *>(h); }
inline whisper_state   *st_of (jlong h) { return reinterpret_cast<whisper_state   *>(h); }

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Trim whisper's leading space on segment text. With max_len=1 each segment is
// one word, and every one of them arrives space-prefixed.
std::string trim(const std::string &s) {
    size_t b = s.find_first_not_of(" \t\n\r");
    if (b == std::string::npos) return {};
    size_t e = s.find_last_not_of(" \t\n\r");
    return s.substr(b, e - b + 1);
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(WHISPER_VERSION);
}

/**
 * Loads model weights. dtwPreset is a whisper_alignment_heads_preset ordinal;
 * pass WHISPER_AHEADS_NONE (0) to disable DTW alignment.
 *
 * DTW matters here: heuristic token timestamps jitter 100-400 ms, which is
 * enough to make tapping a word play the wrong one.
 */
JNIEXPORT jlong JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeInitContext(
        JNIEnv *env, jobject, jstring modelPath, jint dtwPreset) {
    const std::string path = jstr(env, modelPath);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // Adreno 619 + ggml Vulkan = DeviceLost
    cparams.flash_attn = false;

    if (dtwPreset != WHISPER_AHEADS_NONE) {
        cparams.dtw_token_timestamps = true;
        cparams.dtw_aheads_preset =
                static_cast<whisper_alignment_heads_preset>(dtwPreset);
        // 0 lets whisper size the DTW scratch buffer itself.
        cparams.dtw_mem_size = 0;
    }

    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        return 0;
    }
    LOGI("loaded model %s (dtw preset %d)", path.c_str(), dtwPreset);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeFreeContext(JNIEnv *, jobject, jlong ctx) {
    if (ctx) whisper_free(ctx_of(ctx));
}

JNIEXPORT jlong JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeInitState(JNIEnv *, jobject, jlong ctx) {
    if (!ctx) return 0;
    whisper_state *st = whisper_init_state(ctx_of(ctx));
    return reinterpret_cast<jlong>(st);
}

JNIEXPORT void JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeFreeState(JNIEnv *, jobject, jlong st) {
    if (st) whisper_free_state(st_of(st));
}

/**
 * Encoder-only language identification over the 30 s window starting at
 * offsetMs. Far cheaper than a decode, which is what makes per-window
 * detection practical.
 *
 * @return probability per language id, indexed by whisper_lang_id().
 */
JNIEXPORT jfloatArray JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeDetectLanguage(
        JNIEnv *env, jobject, jlong ctx, jlong st,
        jfloatArray pcm, jint offsetMs, jint nThreads) {
    if (!ctx || !st) return nullptr;

    const jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);

    // The mel spectrogram must exist before language detection can read it.
    if (whisper_pcm_to_mel_with_state(ctx_of(ctx), st_of(st), samples, n, nThreads) != 0) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        LOGE("pcm_to_mel failed");
        return nullptr;
    }
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    const int n_langs = whisper_lang_max_id() + 1;
    std::vector<float> probs(n_langs, 0.0f);
    const int best = whisper_lang_auto_detect_with_state(
            ctx_of(ctx), st_of(st), offsetMs, nThreads, probs.data());
    if (best < 0) {
        LOGE("lang_auto_detect failed: %d", best);
        return nullptr;
    }

    jfloatArray out = env->NewFloatArray(n_langs);
    env->SetFloatArrayRegion(out, 0, n_langs, probs.data());
    return out;
}

/** Maps an ISO 639-1 code to whisper's language id, or -1 if unknown. */
JNIEXPORT jint JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeLangId(JNIEnv *env, jobject, jstring code) {
    return whisper_lang_id(jstr(env, code).c_str());
}

/**
 * Full decode of one window, forced to `language`.
 *
 * maxLen=1 makes whisper emit one word per segment, which is what gives the UI
 * per-word tap targets.
 */
JNIEXPORT jboolean JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeFull(
        JNIEnv *env, jobject, jlong ctx, jlong st, jfloatArray pcm,
        jstring language, jint nThreads, jint beamSize, jint maxLen,
        jboolean noContext, jboolean tokenTimestamps) {
    if (!ctx || !st) return JNI_FALSE;

    const std::string lang = jstr(env, language);

    whisper_full_params p = whisper_full_default_params(
            beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.print_realtime   = false;
    p.print_progress   = false;
    p.print_timestamps = false;
    p.print_special    = false;
    p.translate        = false;   // transcribe Farsi as Farsi, never to English
    p.language         = lang.empty() ? nullptr : lang.c_str();
    p.detect_language  = false;   // the caller already decided
    p.n_threads        = nThreads;
    p.no_context       = noContext == JNI_TRUE;
    p.token_timestamps = tokenTimestamps == JNI_TRUE;
    p.max_len          = maxLen;
    p.single_segment   = false;
    if (beamSize > 1) p.beam_search.beam_size = beamSize;

    const jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    const int rc = whisper_full_with_state(ctx_of(ctx), st_of(st), p, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeWordCount(JNIEnv *, jobject, jlong st) {
    if (!st) return 0;
    return whisper_full_n_segments_from_state(st_of(st));
}

/**
 * One word, packed as "text\tt0Ms\tt1Ms\tprob\tsegmentIdx".
 *
 * Packing avoids five JNI round-trips per word; a minute of speech is a couple
 * hundred words and the crossings add up.
 */
JNIEXPORT jstring JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeWordAt(JNIEnv *env, jobject, jlong st, jint i) {
    if (!st) return nullptr;
    whisper_state *state = st_of(st);
    if (i < 0 || i >= whisper_full_n_segments_from_state(state)) return nullptr;

    const char *raw = whisper_full_get_segment_text_from_state(state, i);
    const std::string text = trim(raw ? raw : "");

    // whisper reports centiseconds.
    const int64_t t0 = whisper_full_get_segment_t0_from_state(state, i) * 10;
    const int64_t t1 = whisper_full_get_segment_t1_from_state(state, i) * 10;

    // Mean token probability across the segment: with max_len=1 that is
    // usually a single token, but words can split into several.
    float sum = 0.0f;
    const int nTok = whisper_full_n_tokens_from_state(state, i);
    int counted = 0;
    for (int j = 0; j < nTok; ++j) {
        sum += whisper_full_get_token_p_from_state(state, i, j);
        ++counted;
    }
    const float prob = counted > 0 ? sum / static_cast<float>(counted) : 0.0f;

    char buf[64];
    snprintf(buf, sizeof(buf), "\t%lld\t%lld\t%.4f\t%d",
             static_cast<long long>(t0), static_cast<long long>(t1), prob, i);
    return env->NewStringUTF((text + buf).c_str());
}

/**
 * Mean log probability over every token of the decode — the signal used to
 * choose between the English and Farsi attempts at the same audio.
 *
 * Whisper forced to the wrong language does not fail quietly; it emits fluent
 * nonsense. This is the only confidence measure the API offers.
 */
JNIEXPORT jfloat JNICALL
Java_dev_bscribe_asr_WhisperNative_nativeAvgLogProb(JNIEnv *, jobject, jlong st) {
    if (!st) return -1e9f;
    whisper_state *state = st_of(st);

    double sum = 0.0;
    int count = 0;
    const int nSeg = whisper_full_n_segments_from_state(state);
    for (int i = 0; i < nSeg; ++i) {
        const int nTok = whisper_full_n_tokens_from_state(state, i);
        for (int j = 0; j < nTok; ++j) {
            const float p = whisper_full_get_token_p_from_state(state, i, j);
            sum += std::log(p > 1e-9f ? p : 1e-9f);
            ++count;
        }
    }
    if (count == 0) return -1e9f;
    return static_cast<jfloat>(sum / count);
}

} // extern "C"
