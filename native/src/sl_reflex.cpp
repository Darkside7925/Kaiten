// sl_reflex.cpp â€” NVIDIA Reflex low-latency: options, sleep, PCL markers, state.
// Reflex is the mandatory dependency for DLSS Frame Generation. Requires the device set.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <jni.h>
#include <cstdio>

#include "mcdlss.h"
#include "sl.h"
#include "sl_reflex.h"
#include "sl_pcl.h"

static sl::FrameToken* tokenFor(uint32_t frameIndex) {
    sl::FrameToken* t = nullptr;
    slGetNewFrameToken(t, &frameIndex);
    return t;
}

extern "C" {

// int NativeBridge.slReflexSetOptionsNative(mode, frameLimitUs)
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slReflexSetOptionsNative(JNIEnv*, jclass, jint mode, jint frameLimitUs) {
    sl::ReflexOptions opt{};
    opt.mode = (sl::ReflexMode)mode;
    opt.frameLimitUs = (uint32_t)frameLimitUs;
    opt.useMarkersToOptimize = true;
    return (jint)slReflexSetOptions(opt);
}

// int NativeBridge.slReflexSleepNative(frameIndex) â€” the latency sleep point.
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slReflexSleepNative(JNIEnv*, jclass, jint frameIndex) {
    sl::FrameToken* t = tokenFor((uint32_t)frameIndex);
    if (!t) return (jint)sl::Result::eErrorInvalidParameter;
    return (jint)slReflexSleep(*t);
}

// int NativeBridge.slPclMarkerNative(marker, frameIndex)
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slPclMarkerNative(JNIEnv*, jclass, jint marker, jint frameIndex) {
    sl::FrameToken* t = tokenFor((uint32_t)frameIndex);
    if (!t) return (jint)sl::Result::eErrorInvalidParameter;
    return (jint)slPCLSetMarker((sl::PCLMarker)marker, *t);
}

// String NativeBridge.slReflexStateNative()
JNIEXPORT jstring JNICALL
Java_net_kaiten_NativeBridge_slReflexStateNative(JNIEnv* env, jclass) {
    sl::ReflexState state{};
    sl::Result r = slReflexGetState(state);
    char buf[160];
    if (r != sl::Result::eOk) std::snprintf(buf, sizeof(buf), "state query failed: %d", (int)r);
    else std::snprintf(buf, sizeof(buf), "lowLatencyAvailable=%s, latencyReportAvailable=%s",
                       state.lowLatencyAvailable ? "true" : "false",
                       state.latencyReportAvailable ? "true" : "false");
    return env->NewStringUTF(buf);
}

} // extern "C"
