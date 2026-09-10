#include "frontend.h"
#include "log.h"
#include <android/native_window_jni.h>
#include <jni.h>
#include <string>

namespace {

JavaVM* g_vm = nullptr;
jclass g_bridgeClass = nullptr;
jmethodID g_onMessage = nullptr, g_onRumble = nullptr, g_onGeometry = nullptr, g_onShutdown = nullptr, g_onFatal = nullptr;

struct ScopedEnv {
    JNIEnv* env = nullptr;
    bool attached = false;
    ScopedEnv() {
        if (!g_vm) return;
        if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            JavaVMAttachArgs args{ JNI_VERSION_1_6, "OneEmuEmu", nullptr };
            if (g_vm->AttachCurrentThread(&env, &args) == JNI_OK) attached = true;
            else env = nullptr;
        }
    }
    ~ScopedEnv() { if (attached && g_vm) g_vm->DetachCurrentThread(); }
};

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return out;
}

struct JavaListener : FrontendListener {
    void onMessage(const std::string& msg, unsigned durationMs, int priority) override {
        ScopedEnv se; if (!se.env) return;
        jstring js = se.env->NewStringUTF(msg.c_str());
        se.env->CallStaticVoidMethod(g_bridgeClass, g_onMessage, js, (jint)durationMs, (jint)priority);
        se.env->DeleteLocalRef(js);
    }
    void onRumble(unsigned port, unsigned strength) override {
        ScopedEnv se; if (!se.env) return;
        se.env->CallStaticVoidMethod(g_bridgeClass, g_onRumble, (jint)port, (jint)strength);
    }
    void onGeometryChanged(unsigned w, unsigned h, float aspect) override {
        ScopedEnv se; if (!se.env) return;
        se.env->CallStaticVoidMethod(g_bridgeClass, g_onGeometry, (jint)w, (jint)h, (jfloat)aspect);
    }
    void onCoreShutdown() override {
        ScopedEnv se; if (!se.env) return;
        se.env->CallStaticVoidMethod(g_bridgeClass, g_onShutdown);
    }
    void onEmuThreadStarted() override {
        JNIEnv* env = nullptr;
        if (g_vm && g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            JavaVMAttachArgs args{ JNI_VERSION_1_6, "OneEmuEmu", nullptr };
            g_vm->AttachCurrentThread(&env, &args); // stays attached for the thread's lifetime
        }
    }
    void onEmuThreadStopping() override { if (g_vm) g_vm->DetachCurrentThread(); }
    void onFatal(const std::string& what, int errorCode) override {
        ScopedEnv se; if (!se.env) return;
        jstring js = se.env->NewStringUTF(what.c_str());
        se.env->CallStaticVoidMethod(g_bridgeClass, g_onFatal, js, (jint)errorCode);
        se.env->DeleteLocalRef(js);
    }
};

JavaListener g_listener;
std::string g_lastError;

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("com/manggome/oneemu/emu/NativeBridge");
    if (!cls) return JNI_ERR;
    g_bridgeClass = (jclass)env->NewGlobalRef(cls);
    g_onMessage = env->GetStaticMethodID(cls, "onCoreMessage", "(Ljava/lang/String;II)V");
    g_onRumble = env->GetStaticMethodID(cls, "onRumble", "(II)V");
    g_onGeometry = env->GetStaticMethodID(cls, "onGeometryChanged", "(IIF)V");
    g_onShutdown = env->GetStaticMethodID(cls, "onCoreShutdown", "()V");
    g_onFatal = env->GetStaticMethodID(cls, "onFatal", "(Ljava/lang/String;I)V");
    Frontend::get().setListener(&g_listener);
    return JNI_VERSION_1_6;
}

#define BRIDGE(ret, name) JNIEXPORT ret JNICALL Java_com_manggome_oneemu_emu_NativeBridge_##name

BRIDGE(jboolean, loadCoreNative)(JNIEnv* env, jobject, jstring corePath, jstring systemDir, jstring saveDir, jstring options,
                                 jboolean strictGlesVersion) {
    g_lastError.clear();
    return Frontend::get().loadCore(jstr(env, corePath), jstr(env, systemDir), jstr(env, saveDir), jstr(env, options),
                                    strictGlesVersion, &g_lastError);
}

BRIDGE(jboolean, loadGame)(JNIEnv* env, jobject, jstring romPath) {
    g_lastError.clear();
    return Frontend::get().loadGame(jstr(env, romPath), &g_lastError);
}

BRIDGE(jstring, lastError)(JNIEnv* env, jobject) { return env->NewStringUTF(g_lastError.c_str()); }
BRIDGE(jint, lastErrorCode)(JNIEnv*, jobject) { return (jint)Frontend::get().lastErrorCode(); }
BRIDGE(jstring, getRecentCoreLog)(JNIEnv* env, jobject) { return env->NewStringUTF(Frontend::get().recentLog().c_str()); }

BRIDGE(void, unload)(JNIEnv*, jobject) { Frontend::get().unload(); }

BRIDGE(void, setSurface)(JNIEnv* env, jobject, jobject surface) {
    ANativeWindow* w = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    Frontend::get().setSurface(w);
    if (w) ANativeWindow_release(w); // Frontend acquired its own reference
}

BRIDGE(void, setSurfaceSize)(JNIEnv*, jobject, jint w, jint h) { Frontend::get().setSurfaceSize(w, h); }
BRIDGE(void, setPaused)(JNIEnv*, jobject, jboolean p) { Frontend::get().setPaused(p); }
BRIDGE(jboolean, isRunning)(JNIEnv*, jobject) { return Frontend::get().isRunning(); }

BRIDGE(void, setInput)(JNIEnv*, jobject, jint port, jint buttons, jint lx, jint ly, jint rx, jint ry) {
    Frontend::get().setInput((unsigned)port, (uint32_t)buttons, (int16_t)lx, (int16_t)ly, (int16_t)rx, (int16_t)ry);
}
BRIDGE(void, setPointer)(JNIEnv*, jobject, jint x, jint y, jboolean pressed) {
    Frontend::get().setPointer((int16_t)x, (int16_t)y, pressed);
}
BRIDGE(void, setFastForward)(JNIEnv*, jobject, jint speed) { Frontend::get().setFastForward(speed); }
BRIDGE(void, setVideoConfig)(JNIEnv*, jobject, jboolean linear, jint aspect) { Frontend::get().setVideoConfig(linear, aspect); }
BRIDGE(void, setAudioMuted)(JNIEnv*, jobject, jboolean muted) { Frontend::get().setAudioMuted(muted); }

BRIDGE(jboolean, saveState)(JNIEnv* env, jobject, jstring path) { return Frontend::get().saveState(jstr(env, path)); }
BRIDGE(jboolean, loadState)(JNIEnv* env, jobject, jstring path) { return Frontend::get().loadState(jstr(env, path)); }
BRIDGE(jboolean, saveSram)(JNIEnv*, jobject) { return Frontend::get().saveSram(); }
BRIDGE(void, reset)(JNIEnv*, jobject) { Frontend::get().reset(); }

// Returns [w, h, argb pixels...] or null.
BRIDGE(jintArray, screenshot)(JNIEnv* env, jobject) {
    std::vector<uint32_t> rgba;
    int w = 0, h = 0;
    if (!Frontend::get().screenshot(rgba, w, h)) return nullptr;
    jintArray arr = env->NewIntArray(2 + w * h);
    if (!arr) return nullptr;
    std::vector<jint> out(2 + (size_t)w * h);
    out[0] = w; out[1] = h;
    for (size_t i = 0; i < (size_t)w * h; i++) {
        uint32_t p = rgba[i]; // memory order R,G,B,A → little-endian uint32 = 0xAABBGGRR
        uint32_t r = p & 0xff, g = (p >> 8) & 0xff, b = (p >> 16) & 0xff;
        out[2 + i] = (jint)(0xff000000u | (r << 16) | (g << 8) | b);
    }
    env->SetIntArrayRegion(arr, 0, (jsize)out.size(), out.data());
    return arr;
}

BRIDGE(void, setCheat)(JNIEnv* env, jobject, jint index, jboolean enabled, jstring code) {
    Frontend::get().setCheat((unsigned)index, enabled, jstr(env, code));
}
BRIDGE(void, resetCheats)(JNIEnv*, jobject) { Frontend::get().resetCheats(); }

// One option per line: key\tdesc\tinfo\tcategory\tcurrent\tdefault\tvisible\tval1=label1|val2=label2...
BRIDGE(jstring, getOptions)(JNIEnv* env, jobject) {
    std::string out;
    for (auto& o : Frontend::get().options()) {
        out += o.key + "\t" + o.desc + "\t" + o.info + "\t" + o.category + "\t" + o.current + "\t" + o.defaultValue + "\t" + (o.visible ? "1" : "0") + "\t";
        bool first = true;
        for (auto& v : o.values) {
            if (!first) out += "|";
            first = false;
            out += v.first + "=" + v.second;
        }
        out += "\n";
    }
    return env->NewStringUTF(out.c_str());
}
BRIDGE(void, setOption)(JNIEnv* env, jobject, jstring key, jstring value) {
    Frontend::get().setOption(jstr(env, key), jstr(env, value));
}

// [baseW, baseH, maxW, maxH, aspect*10000, fps*1000, sampleRate]
BRIDGE(jintArray, getAvInfo)(JNIEnv* env, jobject) {
    auto av = Frontend::get().avInfo();
    jint vals[7] = { (jint)av.geometry.base_width, (jint)av.geometry.base_height, (jint)av.geometry.max_width,
                     (jint)av.geometry.max_height, (jint)(av.geometry.aspect_ratio * 10000), (jint)(av.timing.fps * 1000),
                     (jint)av.timing.sample_rate };
    jintArray arr = env->NewIntArray(7);
    env->SetIntArrayRegion(arr, 0, 7, vals);
    return arr;
}
BRIDGE(jdouble, getFps)(JNIEnv*, jobject) { return Frontend::get().fps(); }
BRIDGE(jstring, getCoreName)(JNIEnv* env, jobject) { return env->NewStringUTF(Frontend::get().coreName().c_str()); }

} // extern "C"
