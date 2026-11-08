#include <jni.h>
#include <llama.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <algorithm>

struct TextEngine { llama_model * model; llama_context * ctx; std::atomic<bool> cancelled{false}; };
static void fail(JNIEnv * e, const char * message) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"), message); }
static bool aborted(void * data) { return static_cast<TextEngine *>(data)->cancelled.load(); }
extern "C" JNIEXPORT jlong JNICALL Java_ru_localvoices_llm_LocalLlm_open(JNIEnv * e,jobject,jstring path,jint threads) {
    static std::once_flag init; std::call_once(init, []{ llama_backend_init(); });
    const char * p=e->GetStringUTFChars(path,nullptr);
    auto mp=llama_model_default_params(); mp.n_gpu_layers=0; mp.use_mmap=true;
    auto model=llama_model_load_from_file(p,mp); e->ReleaseStringUTFChars(path,p);
    if(!model){fail(e,"Cannot load punctuation GGUF");return 0;}
    auto params=llama_context_default_params(); params.n_ctx=1024; params.n_batch=512;
    params.n_threads=threads; params.n_threads_batch=threads;
    auto ctx=llama_init_from_model(model,params);
    if(!ctx){llama_model_free(model);fail(e,"Cannot create local LLM context");return 0;}
    auto engine=new TextEngine{model,ctx}; llama_set_abort_callback(ctx,aborted,engine);
    return reinterpret_cast<jlong>(engine);
}
extern "C" JNIEXPORT void JNICALL Java_ru_localvoices_llm_LocalLlm_abort(JNIEnv *,jobject,jlong h){ reinterpret_cast<TextEngine *>(h)->cancelled=true; }
extern "C" JNIEXPORT void JNICALL Java_ru_localvoices_llm_LocalLlm_release(JNIEnv *,jobject,jlong h){auto t=reinterpret_cast<TextEngine *>(h);llama_free(t->ctx);llama_model_free(t->model);delete t;}
extern "C" JNIEXPORT jbyteArray JNICALL Java_ru_localvoices_llm_LocalLlm_generate(JNIEnv * e,jobject,jlong h,jbyteArray input){
    auto t=reinterpret_cast<TextEngine *>(h);
    std::string text(e->GetArrayLength(input),' '); e->GetByteArrayRegion(input,0,text.size(),reinterpret_cast<jbyte *>(text.data()));
    const std::string prompt="<|im_start|>system\nRestore punctuation and letter case in Russian or English text. Copy every word and number exactly in the same order. Do not translate, explain, add or remove words. Output only the corrected text.<|im_end|>\n<|im_start|>user\n"+text+"<|im_end|>\n<|im_start|>assistant\n";
    const auto vocab=llama_model_get_vocab(t->model);
    int n=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,true,true);
    if(n<=0||n>700){fail(e,"LLM input exceeds context");return nullptr;}
    std::vector<llama_token> tokens(n);llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),n,true,true);
    llama_memory_clear(llama_get_memory(t->ctx),true);
    for(int pos=0;pos<n;pos+=512){auto batch=llama_batch_get_one(tokens.data()+pos,std::min(512,n-pos));if(llama_decode(t->ctx,batch)){fail(e,"Local LLM prompt decode failed");return nullptr;}}
    auto sampler=llama_sampler_init_greedy();std::string result;
    for(int i=0;i<256 && n+i<1020 && !t->cancelled;i++){
        auto token=llama_sampler_sample(sampler,t->ctx,-1);
        if(llama_vocab_is_eog(vocab,token))break;
        char buffer[256];int len=llama_token_to_piece(vocab,token,buffer,sizeof(buffer),0,false);
        if(len>0)result.append(buffer,len);
        auto batch=llama_batch_get_one(&token,1);if(llama_decode(t->ctx,batch)){llama_sampler_free(sampler);fail(e,"Local LLM generation failed");return nullptr;}
    }
    llama_sampler_free(sampler);
    if(t->cancelled){fail(e,"Local LLM cancelled");return nullptr;}
    auto out=e->NewByteArray(result.size());e->SetByteArrayRegion(out,0,result.size(),reinterpret_cast<const jbyte *>(result.data()));return out;
}
