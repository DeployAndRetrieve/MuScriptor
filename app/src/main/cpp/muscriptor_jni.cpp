#include <jni.h>
#include <dlfcn.h>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Registry;
struct Model;
struct Session;
struct Request;
struct Result;
struct Options;
using Status = int;
constexpr int OK = 0;
struct ModelConfig { const char *family_hint; const char *config_id; const char *weight_id; const char *model_spec_override; };
struct BackendConfig { const char *backend; int device; int threads; };

struct Api {
    void * handle = nullptr;
    uint32_t (*abi_version)() = nullptr;
    const char * (*last_error)() = nullptr;
    Status (*registry_create)(const char *, Registry **) = nullptr;
    void (*registry_free)(Registry *) = nullptr;
    Status (*model_load)(Registry *, const char *, const ModelConfig *, const Options *, Model **) = nullptr;
    void (*model_free)(Model *) = nullptr;
    Status (*session_create)(const Model *, const char *, const char *, const BackendConfig *, const Options *, Session **) = nullptr;
    void (*session_free)(Session *) = nullptr;
    Request * (*request_create)() = nullptr;
    void (*request_free)(Request *) = nullptr;
    Status (*request_audio)(Request *, const float *, size_t, int, int) = nullptr;
    Status (*request_option)(Request *, const char *, const char *) = nullptr;
    Status (*session_run)(Session *, const Request *, Result **) = nullptr;
    Status (*result_text)(const Result *, const char **, const char **) = nullptr;
    void (*result_free)(Result *) = nullptr;

    template <typename T> void bind(T &slot, const char *name) {
        slot = reinterpret_cast<T>(dlsym(handle,name));
        if (!slot) throw std::runtime_error(std::string("C API symbol missing: ") + name);
    }
    Api() {
        handle = dlopen("libaudiocpp.so", RTLD_NOW | RTLD_LOCAL);
        if (!handle) throw std::runtime_error(std::string("libaudiocpp.so is absent or cannot load: ") + dlerror());
        try {
            bind(abi_version,"audiocpp_abi_version");
            if ((abi_version() >> 16) != 0) throw std::runtime_error("Unsupported audio.cpp ABI major");
            bind(last_error,"audiocpp_last_error");
            bind(registry_create,"audiocpp_registry_create");
            bind(registry_free,"audiocpp_registry_free");
            bind(model_load,"audiocpp_model_load");
            bind(model_free,"audiocpp_model_free");
            bind(session_create,"audiocpp_session_create");
            bind(session_free,"audiocpp_session_free");
            bind(request_create,"audiocpp_request_create");
            bind(request_free,"audiocpp_request_free");
            bind(request_audio,"audiocpp_request_set_audio");
            bind(request_option,"audiocpp_request_set_option");
            bind(session_run,"audiocpp_session_run");
            bind(result_text,"audiocpp_result_text");
            bind(result_free,"audiocpp_result_free");
        } catch (...) { dlclose(handle); handle=nullptr; throw; }
    }
    ~Api() { if(handle)dlclose(handle); }
    void check(Status status, const char *where) {
        if (status != OK) throw std::runtime_error(std::string(where)+": "+last_error());
    }
};
struct UtfString {
    JNIEnv *env; jstring s; const char *p;
    UtfString(JNIEnv *e, jstring value):env(e),s(value),p(e->GetStringUTFChars(value,nullptr)){}
    ~UtfString(){if(p)env->ReleaseStringUTFChars(s,p);}
};
struct Objects {
    Api &a; Registry *registry=nullptr; Model *model=nullptr; Session *session=nullptr;
    Request *request=nullptr; Result *result=nullptr;
    explicit Objects(Api &api):a(api){}
    ~Objects(){
        if(result)a.result_free(result);
        if(request)a.request_free(request);
        if(session)a.session_free(session);
        if(model)a.model_free(model);
        if(registry)a.registry_free(registry);
    }
};
void throwJava(JNIEnv* env,const std::exception &e){
    jclass c=env->FindClass("java/lang/IllegalStateException");
    if(c)env->ThrowNew(c,e.what());
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_deployandretrieve_muscriptorscore_NativeBridge_nativeEngineStatus(JNIEnv *env,jclass){
    try {
        Api a;
        std::string s="audio.cpp C API v" + std::to_string(a.abi_version()>>16) + "." + std::to_string((a.abi_version()>>8)&255) + "." + std::to_string(a.abi_version()&255);
        return env->NewStringUTF(s.c_str());
    } catch (const std::exception &e) {
        return env->NewStringUTF(e.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_deployandretrieve_muscriptorscore_NativeBridge_nativeAnalyze(
        JNIEnv *env,jclass,jstring modelPath,jfloatArray pcm,jint sampleRate,jint maxTokens) {
    try {
        if (!modelPath || !pcm || sampleRate < 8000 || maxTokens <= 0)
            throw std::runtime_error("Invalid inference input");
        Api a;
        Objects o(a);
        UtfString path(env,modelPath);
        if (!path.p)throw std::runtime_error("Could not read model file path");
        a.check(a.registry_create(nullptr,&o.registry),"registry_create");
        ModelConfig conf{"muscriptor", nullptr, nullptr, nullptr};
        a.check(a.model_load(o.registry,path.p,&conf,nullptr,&o.model),"model_load");
        BackendConfig backend{"cpu",0,2};
        a.check(a.session_create(o.model,"midi","offline",&backend,nullptr,&o.session),"session_create");
        o.request=a.request_create();
        if(!o.request)throw std::runtime_error("request_create failed");
        const jsize length=env->GetArrayLength(pcm);
        if (length<=0)throw std::runtime_error("Empty audio");
        jfloat *array=env->GetFloatArrayElements(pcm,nullptr);
        if (!array)throw std::runtime_error("Out of memory obtaining audio buffer");
        Status rs=a.request_audio(o.request,array,static_cast<size_t>(length),sampleRate,1);
        env->ReleaseFloatArrayElements(pcm,array,JNI_ABORT);
        a.check(rs,"set_audio");
        a.check(a.request_option(o.request,"output_format","json"),"output_format");
        std::string tokens=std::to_string(maxTokens);
        a.check(a.request_option(o.request,"max_tokens",tokens.c_str()),"max_tokens");
        a.check(a.request_option(o.request,"do_sample","false"),"do_sample");
        a.check(a.session_run(o.session,o.request,&o.result),"session_run");
        const char *json=nullptr;
        const char *language=nullptr;
        a.check(a.result_text(o.result,&json,&language),"result_text");
        if (!json)throw std::runtime_error("No note events returned");
        return env->NewStringUTF(json);
    }catch(const std::exception &e){throwJava(env,e);return nullptr;}
}
