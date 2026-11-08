#include <jni.h>
#include <string>
#include <vector>
#include "transcribe.h"

static void fail(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, message == nullptr ? "GigaAM error" : message);
}

extern "C" JNIEXPORT jlong JNICALL Java_ru_localvoices_ml_GigaAmNative_open(JNIEnv * env, jclass, jstring path, jint threads) {
    const char * utf8 = env->GetStringUTFChars(path, nullptr);
    transcribe_model_load_params load{};
    transcribe_model_load_params_init(&load);
    load.backend = TRANSCRIBE_BACKEND_CPU;
    transcribe_session_params session_params{};
    transcribe_session_params_init(&session_params);
    session_params.n_threads = threads;
    transcribe_session * session = nullptr;
    const transcribe_status status = transcribe_open(utf8, &load, &session_params, &session);
    env->ReleaseStringUTFChars(path, utf8);
    if (status != TRANSCRIBE_OK || session == nullptr) { fail(env, transcribe_status_string(status)); return 0; }
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL Java_ru_localvoices_ml_GigaAmNative_close(JNIEnv *, jclass, jlong ptr) {
    transcribe_close(reinterpret_cast<transcribe_session *>(ptr));
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_ru_localvoices_ml_GigaAmNative_transcribe(JNIEnv * env, jclass, jlong ptr, jfloatArray input, jstring language) {
    auto * session = reinterpret_cast<transcribe_session *>(ptr);
    const int n = env->GetArrayLength(input);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(input, 0, n, pcm.data());
    const char * lang = env->GetStringUTFChars(language, nullptr);
    transcribe_run_params params{};
    transcribe_run_params_init(&params);
    params.task = TRANSCRIBE_TASK_TRANSCRIBE;
    params.timestamps = TRANSCRIBE_TIMESTAMPS_TOKEN;
    params.language = lang;
    const transcribe_status status = transcribe_run(session, pcm.data(), n, &params);
    env->ReleaseStringUTFChars(language, lang);
    if (status != TRANSCRIBE_OK) { fail(env, transcribe_status_string(status)); return nullptr; }
    const int count = transcribe_n_tokens(session);
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray result = env->NewObjectArray(count, byteArrayClass, nullptr);
    for (int i = 0; i < count; ++i) {
        transcribe_token token{}; transcribe_token_init(&token); transcribe_get_token(session, i, &token);
        const std::string text = std::to_string(token.id) + "\t" +
            std::to_string(token.t0_ms) + "\t" + std::to_string(token.t1_ms) + "\t" +
            std::to_string(token.seg_index) + "\t" + std::to_string(token.word_index) + "\t" +
            std::to_string(token.p) + "\t" + (token.text == nullptr ? "" : token.text);
        jbyteArray row = env->NewByteArray(static_cast<jsize>(text.size()));
        env->SetByteArrayRegion(row, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
        env->SetObjectArrayElement(result, i, row); env->DeleteLocalRef(row);
    }
    return result;
}
