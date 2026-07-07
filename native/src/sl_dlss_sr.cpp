// sl_dlss_sr.cpp â€” DLSS Super Resolution: Vulkan device hand-off + optimal-settings query.
//
// To call the DLSS feature functions (slDLSS*), Streamline needs the Vulkan device set via
// slSetVulkanInfo (manual-hooking mode â€” we do NOT use SL's vkCreateDevice proxies because
// LWJGL loads the real Vulkan loader). We avoid pulling in vulkan.h by declaring a
// layout-compatible sl::VulkanInfo with void* handles (VkDevice/VkInstance/VkPhysicalDevice
// are opaque pointers) and the unmangled extern "C" slSetVulkanInfo symbol.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <jni.h>
#include <cstdio>

#include "mcdlss.h"
#include "sl.h"
#include "sl_core_api.h"
#include "sl_dlss.h"
#include "sl_matrix_helpers.h"

// Shared frame token (used by both SR and FG to avoid duplicate slGetNewFrameToken)
sl::FrameToken* g_sharedToken = nullptr;
uint32_t g_sharedTokenFrame = 0xFFFFFFFF;

namespace sl {
// Layout-compatible replica of sl_helpers_vk.h's VulkanInfo (handles are pointers).
SL_STRUCT_BEGIN(VulkanInfo, StructType({ 0xeed6fd5, 0x82cd, 0x43a9, { 0xbd, 0xb5, 0x47, 0xa5, 0xba, 0x2f, 0x45, 0xd6 } }), kStructVersion3)
    void* device{};
    void* instance{};
    void* physicalDevice{};
    uint32_t computeQueueIndex{};
    uint32_t computeQueueFamily{};
    uint32_t graphicsQueueIndex{};
    uint32_t graphicsQueueFamily{};
    uint32_t opticalFlowQueueIndex{};
    uint32_t opticalFlowQueueFamily{};
    bool useNativeOpticalFlowMode = false;
    uint32_t computeQueueCreateFlags{};
    uint32_t graphicsQueueCreateFlags{};
    uint32_t opticalFlowQueueCreateFlags{};
SL_STRUCT_END()
}

// SL_API resolves to extern "C" for consumers, so this links to the real (unmangled) symbol.
extern "C" sl::Result slSetVulkanInfo(const sl::VulkanInfo& info);

static std::string joinList(const char** arr, uint32_t n) {
    std::string s;
    for (uint32_t i = 0; i < n; ++i) { if (i) s += "\n"; s += (arr && arr[i]) ? arr[i] : ""; }
    return s;
}

extern "C" {

// String NativeBridge.slDlssDeviceExtensionsNative() â€” newline-joined VK device extensions DLSS needs.
JNIEXPORT jstring JNICALL
Java_net_kaiten_NativeBridge_slDlssDeviceExtensionsNative(JNIEnv* env, jclass) {
    sl::FeatureRequirements req{};
    if (slGetFeatureRequirements(sl::kFeatureDLSS, req) != sl::Result::eOk) return env->NewStringUTF("");
    return env->NewStringUTF(joinList(req.vkDeviceExtensions, req.vkNumDeviceExtensions).c_str());
}

// String NativeBridge.slDlssInstanceExtensionsNative() â€” newline-joined VK instance extensions DLSS needs.
JNIEXPORT jstring JNICALL
Java_net_kaiten_NativeBridge_slDlssInstanceExtensionsNative(JNIEnv* env, jclass) {
    sl::FeatureRequirements req{};
    if (slGetFeatureRequirements(sl::kFeatureDLSS, req) != sl::Result::eOk) return env->NewStringUTF("");
    return env->NewStringUTF(joinList(req.vkInstanceExtensions, req.vkNumInstanceExtensions).c_str());
}

// String NativeBridge.slDlssFeaturesNative() â€” newline-joined required VK 1.2/1.3 feature names (diagnostic).
JNIEXPORT jstring JNICALL
Java_net_kaiten_NativeBridge_slDlssFeaturesNative(JNIEnv* env, jclass) {
    sl::FeatureRequirements req{};
    if (slGetFeatureRequirements(sl::kFeatureDLSS, req) != sl::Result::eOk) return env->NewStringUTF("");
    std::string s = joinList(req.vkFeatures12, req.vkNumFeatures12);
    std::string f13 = joinList(req.vkFeatures13, req.vkNumFeatures13);
    if (!f13.empty()) { if (!s.empty()) s += "\n"; s += f13; }
    return env->NewStringUTF(s.c_str());
}

// int NativeBridge.slSetVulkanInfoNative(instance, physicalDevice, device, gfxFamily, gfxIndex, cmpFamily, cmpIndex)
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slSetVulkanInfoNative(JNIEnv*, jclass,
        jlong instance, jlong physicalDevice, jlong device,
        jint gfxFamily, jint gfxIndex, jint cmpFamily, jint cmpIndex) {
    sl::VulkanInfo info{};
    info.instance = reinterpret_cast<void*>(instance);
    info.physicalDevice = reinterpret_cast<void*>(physicalDevice);
    info.device = reinterpret_cast<void*>(device);
    info.graphicsQueueFamily = (uint32_t)gfxFamily;
    info.graphicsQueueIndex = (uint32_t)gfxIndex;
    info.computeQueueFamily = (uint32_t)cmpFamily;
    info.computeQueueIndex = (uint32_t)cmpIndex;
    sl::Result r = slSetVulkanInfo(info);
    return (jint)r;
}

// String NativeBridge.slDlssOptimalSettingsNative(outputWidth, outputHeight, mode)
JNIEXPORT jstring JNICALL
Java_net_kaiten_NativeBridge_slDlssOptimalSettingsNative(JNIEnv* env, jclass,
        jint outputWidth, jint outputHeight, jint mode) {
    sl::DLSSOptions options{};
    options.mode = (sl::DLSSMode)mode;
    options.outputWidth = (uint32_t)outputWidth;
    options.outputHeight = (uint32_t)outputHeight;

    sl::DLSSOptimalSettings settings{};
    sl::Result r = slDLSSGetOptimalSettings(options, settings);

    char buf[256];
    if (r != sl::Result::eOk) {
        std::snprintf(buf, sizeof(buf), "query failed: result=%d", (int)r);
    } else {
        std::snprintf(buf, sizeof(buf),
            "render %ux%u (min %ux%u, max %ux%u) sharpness=%.3f",
            settings.optimalRenderWidth, settings.optimalRenderHeight,
            settings.renderWidthMin, settings.renderHeightMin,
            settings.renderWidthMax, settings.renderHeightMax,
            settings.optimalSharpness);
    }
    return env->NewStringUTF(buf);
}

} // extern "C"

// --- DLSS Super Resolution evaluate (synthetic validation harness) ---

static sl::Resource vkResource(jlong image, jlong view, jint layout, jint w, jint h, jint fmt) {
    sl::Resource r(sl::ResourceType::eTex2d, reinterpret_cast<void*>(image), nullptr,
                   reinterpret_cast<void*>(view), (uint32_t)layout);
    r.width = (uint32_t)w; r.height = (uint32_t)h; r.nativeFormat = (uint32_t)fmt;
    r.mipLevels = 1; r.arrayLayers = 1;
    return r;
}

static void setRowMajor(sl::float4x4& m, const float* a) {
    for (int i = 0; i < 4; ++i) m.row[i] = sl::float4(a[i*4+0], a[i*4+1], a[i*4+2], a[i*4+3]);
}

extern "C" {

/**
 * int NativeBridge.slDlssEvaluateNative(viewport, frameIndex, cmdBuffer, mode,
 *     outW, outH, renderW, renderH, handles[8], layouts[4], formats[4], consts[40])
 * Tags the 4 SR resources, sets options + constants, and runs slEvaluateFeature(kFeatureDLSS).
 * handles: colorInImg,colorInView, colorOutImg,colorOutView, depthImg,depthView, mvImg,mvView
 * layouts/formats order: colorIn, colorOut, depth, mv
 * consts: [0..15]=cameraViewToClip(row-major) [16..31]=clipToPrevClip jx jy mvsx mvsy near far fov aspect
 */
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slDlssEvaluateNative(JNIEnv* env, jclass,
        jint viewport, jint frameIndex, jlong cmdBuffer, jint mode,
        jint outW, jint outH, jint renderW, jint renderH,
        jlongArray jHandles, jintArray jLayouts, jintArray jFormats, jfloatArray jConsts) {
    jlong h[8]; env->GetLongArrayRegion(jHandles, 0, 8, h);
    jint lay[4]; env->GetIntArrayRegion(jLayouts, 0, 4, lay);
    jint fmt[4]; env->GetIntArrayRegion(jFormats, 0, 4, fmt);
    jfloat c[40]; env->GetFloatArrayRegion(jConsts, 0, 40, c);

    sl::ViewportHandle vp(viewport);
    sl::CommandBuffer* cmd = reinterpret_cast<sl::CommandBuffer*>(cmdBuffer);

    sl::DLSSOptions opt{};
    opt.mode = (sl::DLSSMode)mode;
    opt.outputWidth = (uint32_t)outW; opt.outputHeight = (uint32_t)outH;
    sl::Result r = slDLSSSetOptions(vp, opt);
    if (r != sl::Result::eOk) { std::fprintf(stderr, "[mcdlss] slDLSSSetOptions=%d\n", (int)r); return (jint)r; }

    uint32_t fi = (uint32_t)frameIndex;
    sl::FrameToken* token = nullptr;
    sl::Result tokenR = slGetNewFrameToken(token, &fi);
    if (tokenR != sl::Result::eOk) { std::fprintf(stderr, "[mcdlss/SR] slGetNewFrameToken=%d\n", (int)tokenR); return (jint)tokenR; }
    // Store for FG to reuse (avoids double-NewFrameToken error).
    g_sharedToken = token;
    g_sharedTokenFrame = fi;

    sl::Constants consts{};
    setRowMajor(consts.cameraViewToClip, &c[0]);
    setRowMajor(consts.clipToPrevClip, &c[16]);
    // Derive the inverse matrices SL also wants (avoids 'invalid' constant warnings).
    sl::matrixFullInvert(consts.clipToCameraView, consts.cameraViewToClip);
    sl::matrixFullInvert(consts.prevClipToClip, consts.clipToPrevClip);
    consts.jitterOffset = sl::float2(c[32], c[33]);
    consts.mvecScale = sl::float2(c[34], c[35]);
    consts.cameraNear = c[36]; consts.cameraFar = c[37];
    consts.cameraFOV = c[38]; consts.cameraAspectRatio = c[39];
    consts.cameraPos = sl::float3(0, 0, 0);
    consts.cameraUp = sl::float3(0, 1, 0);
    consts.cameraRight = sl::float3(1, 0, 0);
    consts.cameraFwd = sl::float3(0, 0, 1);
    consts.depthInverted = sl::Boolean::eFalse;
    // The MV buffer now contains real per-pixel camera motion (DlssMotionVectors compute pass),
    // so camera motion IS included in the motion vectors.
    consts.cameraMotionIncluded = sl::Boolean::eTrue;
    consts.motionVectors3D = sl::Boolean::eFalse;
    consts.reset = sl::Boolean::eFalse;
    r = slSetConstants(consts, *token, vp);
    if (r != sl::Result::eOk) { std::fprintf(stderr, "[mcdlss] slSetConstants=%d\n", (int)r); return (jint)r; }

    // Input resources (color, depth, MV) are at render resolution;
    // output is at display resolution. For DLAA mode these are identical.
    sl::Resource colorIn  = vkResource(h[0], h[1], lay[0], renderW, renderH, fmt[0]);
    sl::Resource colorOut = vkResource(h[2], h[3], lay[1], outW, outH, fmt[1]);
    sl::Resource depth    = vkResource(h[4], h[5], lay[2], renderW, renderH, fmt[2]);
    sl::Resource mvec     = vkResource(h[6], h[7], lay[3], renderW, renderH, fmt[3]);
    sl::Extent renderExt{0, 0, (uint32_t)renderW, (uint32_t)renderH};
    sl::Extent outExt{0, 0, (uint32_t)outW, (uint32_t)outH};
    sl::ResourceTag tags[] = {
        sl::ResourceTag(&colorIn,  sl::kBufferTypeScalingInputColor,  sl::ResourceLifecycle::eOnlyValidNow, &renderExt),
        sl::ResourceTag(&colorOut, sl::kBufferTypeScalingOutputColor, sl::ResourceLifecycle::eOnlyValidNow, &outExt),
        sl::ResourceTag(&depth,    sl::kBufferTypeDepth,              sl::ResourceLifecycle::eValidUntilPresent, &renderExt),
        sl::ResourceTag(&mvec,     sl::kBufferTypeMotionVectors,      sl::ResourceLifecycle::eOnlyValidNow, &renderExt),
    };
    r = slSetTag(vp, tags, 4, cmd);
    if (r != sl::Result::eOk) { std::fprintf(stderr, "[mcdlss] slSetTag=%d\n", (int)r); return (jint)r; }

    const sl::BaseStructure* inputs[] = { &vp };
    r = slEvaluateFeature(sl::kFeatureDLSS, *token, inputs, 1, cmd);
    if (r != sl::Result::eOk) std::fprintf(stderr, "[mcdlss/SR] slEvaluateFeature=%d mode=%d out=%ux%u\n", (int)r, (int)mode, outW, outH);
    return (jint)r;
}

} // extern "C"
