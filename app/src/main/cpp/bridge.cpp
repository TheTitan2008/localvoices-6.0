#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <vector>
#include <string>
#include <algorithm>
#include <stdexcept>

extern "C" int localvoices_encoder_pool(whisper_context *, int, float *, int);
struct Engine { whisper_context * ctx; std::atomic<bool> cancelled{false}; };
static void fail(JNIEnv * env, const char * msg) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), msg); }
extern "C" JNIEXPORT jlong JNICALL Java_ru_localvoices_ml_WhisperNative_open(JNIEnv *env, jobject, jstring path) {
    const char * p = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params(); params.use_gpu = false; params.flash_attn = false;
    auto ctx = whisper_init_from_file_with_params(p, params);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) { fail(env, "Не удалось загрузить модель Whisper"); return 0; }
    return reinterpret_cast<jlong>(new Engine{ctx});
}
extern "C" JNIEXPORT void JNICALL Java_ru_localvoices_ml_WhisperNative_close(JNIEnv *, jobject, jlong ptr) {
    auto e = reinterpret_cast<Engine *>(ptr); if (e) { whisper_free(e->ctx); delete e; }
}
extern "C" JNIEXPORT void JNICALL Java_ru_localvoices_ml_WhisperNative_cancel(JNIEnv *, jobject, jlong ptr) {
    reinterpret_cast<Engine *>(ptr)->cancelled = true;
}
static bool aborted(void * data) { return reinterpret_cast<Engine *>(data)->cancelled.load(); }
// Return UTF-8 byte arrays rather than JNI modified UTF-8 (Cyrillic and emoji safe).
extern "C" JNIEXPORT jobjectArray JNICALL Java_ru_localvoices_ml_WhisperNative_transcribe(JNIEnv *env, jobject, jlong ptr, jfloatArray input, jstring language, jint threads) {
    auto e = reinterpret_cast<Engine *>(ptr);
    const int n = env->GetArrayLength(input); std::vector<float> audio(n); env->GetFloatArrayRegion(input, 0, n, audio.data());
    const char * lang = env->GetStringUTFChars(language, nullptr);
    auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language = lang; p.n_threads = threads;
    p.print_progress = false; p.print_realtime = false; p.print_timestamps = false;
    // Keep token timestamps, but do not cap every segment to one token.  The old
    // max_len=1 setting produced artificial fragments and poor Russian decoding.
    p.token_timestamps = true; p.max_len = 0; p.split_on_word = true;
    p.abort_callback = aborted; p.abort_callback_user_data = e;
    int status = whisper_full(e->ctx, p, audio.data(), n);
    env->ReleaseStringUTFChars(language, lang);
    if (status != 0 || e->cancelled) { fail(env, e->cancelled ? "Обработка отменена" : "Ошибка Whisper"); return nullptr; }
    const int count = whisper_full_n_segments(e->ctx);
    auto result = env->NewObjectArray(count, env->FindClass("[B"), nullptr);
    for (int i = 0; i < count; ++i) {
        std::string line = std::to_string(whisper_full_get_segment_t0(e->ctx, i)) + "\t" +
            std::to_string(whisper_full_get_segment_t1(e->ctx, i)) + "\t" + whisper_full_get_segment_text(e->ctx, i);
        auto bytes = env->NewByteArray(line.size()); env->SetByteArrayRegion(bytes, 0, line.size(), reinterpret_cast<const jbyte *>(line.data()));
        env->SetObjectArrayElement(result, i, bytes); env->DeleteLocalRef(bytes);
    }
    return result;
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_ru_localvoices_ml_WhisperNative_embedding(JNIEnv *env, jobject, jlong ptr, jfloatArray input, jint threads) {
    auto e = reinterpret_cast<Engine *>(ptr);
    int n = env->GetArrayLength(input);
    if (n < 16000 || n > 480000 || e->cancelled) { fail(env, "Whisper embedding требует 1–30 секунд речи"); return nullptr; }
    std::vector<float> audio(n); env->GetFloatArrayRegion(input, 0, n, audio.data());
    if (whisper_pcm_to_mel(e->ctx, audio.data(), n, threads) || whisper_encode(e->ctx, 0, threads)) {
        fail(env, "Не удалось вычислить выход энкодера"); return nullptr;
    }
    std::vector<float> v(2048);
    int dim = localvoices_encoder_pool(e->ctx, (n + 319) / 320, v.data(), v.size());
    if (dim <= 0) { fail(env, "Выход энкодера Whisper недоступен"); return nullptr; }
    auto result = env->NewFloatArray(dim); env->SetFloatArrayRegion(result, 0, dim, v.data()); return result;
}
