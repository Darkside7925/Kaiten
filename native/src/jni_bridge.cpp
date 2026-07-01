// jni_bridge.cpp â€” JNI entrypoints for net.kaiten.NativeBridge.
//
// Phase 0: prove the native library loads inside Minecraft and a JNI round-trip works,
// with NO Streamline dependency yet. Streamline lifecycle (slInit, feature checks, tagging,
// evaluate) is added in sl_manager.cpp in Phase 1 and surfaced through additional natives.

#include <jni.h>
#include <string>
#include "mcdlss.h"

extern "C" {

// jstring net.kaiten.NativeBridge.hello(String from)
JNIEXPORT jstring JNICALL
Java_net_kaiten_NativeBridge_hello(JNIEnv* env, jclass /*clazz*/, jstring from) {
    const char* fromC = from ? env->GetStringUTFChars(from, nullptr) : "";
    std::string msg = std::string(MCDLSS_VERSION_STRING) + " â€” JNI round-trip OK, hello from '" +
                      (fromC ? fromC : "") + "'";
    if (from) env->ReleaseStringUTFChars(from, fromC);
    return env->NewStringUTF(msg.c_str());
}

// int net.kaiten.NativeBridge.abiVersion()
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_abiVersion(JNIEnv* /*env*/, jclass /*clazz*/) {
    return (jint)MCDLSS_ABI_VERSION;
}

} // extern "C"
