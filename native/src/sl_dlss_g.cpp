// sl_dlss_g.cpp â€” DLSS Frame Generation (DLSS-G / Multi-Frame Generation).
//
// DLSS-G interpolates AI-generated frames between fully-rendered frames.
// On Blackwell GPUs this supports 1â†’2Ã—, 1â†’3Ã—, 1â†’4Ã— multipliers (MFG).
//
// Requirements (all gated before this file is compiled):
//   - Phase 1: Streamline init complete, device set via slSetVulkanInfo
//   - Phase 3: DLSS Super Resolution evaluate path proven
//   - Phase 4: Reflex enabled (mandatory for FG)
//   - HAGS enabled in Windows
//   - VSync OFF (FG manages present timing)
//
// FG tagging: slSetTag(HUD-less color, depth, MV, optional UI alpha) â†’
//             slEvaluateFeature(kFeatureDLSS_G) â†’
//             swapchain present (SL owns the present call when FG is active)

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <jni.h>
#include <cstdio>
#include <string>

#include "mcdlss.h"
#include "sl.h"
#include "sl_dlss_g.h"
#include "sl_matrix_helpers.h"

// Shared frame token (from sl_dlss_sr.cpp). SR gets the token first, FG reuses it.
extern sl::FrameToken* g_sharedToken;
extern uint32_t g_sharedTokenFrame;

static sl::Resource vkResource(jlong image, jlong view, jint layout, jint w, jint h, jint fmt) {
    sl::Resource r(sl::ResourceType::eTex2d, reinterpret_cast<void*>(image), nullptr,
                   reinterpret_cast<void*>(view), (uint32_t)layout);
    r.width = (uint32_t)w; r.height = (uint32_t)h; r.nativeFormat = (uint32_t)fmt;
    r.mipLevels = 1; r.arrayLayers = 1;
    return r;
}

static void setRowMajor(sl::float4x4& m, const float* a) {
    for (int i = 0; i < 4; ++i)
        m.row[i] = sl::float4(a[i*4+0], a[i*4+1], a[i*4+2], a[i*4+3]);
}

extern "C" {

// int NativeBridge.slDlssGSetOptionsNative(mode, numFramesToGenerate, flags,
//     width, height, colorFormat, mvFormat, depthFormat)
// mode: 0=Off, 1=On, 2=Auto, 3=Dynamic
// numFramesToGenerate: 1=2x, 2=3x, 3=4x (must not exceed numFramesToGenerateMax)
// flags: DLSSGFlags bits (0x1 = showOnlyInterpolatedFrame, 0x2 = dynamicRes, etc.)
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slDlssGSetOptionsNative(JNIEnv*, jclass,
        jint mode, jint numFramesToGenerate, jint flags,
        jint width, jint height, jint colorFormat, jint mvFormat, jint depthFormat) {
    sl::ViewportHandle vp(0);
    sl::DLSSGOptions opts = {};
    opts.mode = (sl::DLSSGMode)mode;
    opts.numFramesToGenerate = (uint32_t)numFramesToGenerate;
    opts.flags = (sl::DLSSGFlags)flags;
    opts.colorWidth = (uint32_t)width;
    opts.colorHeight = (uint32_t)height;
    opts.mvecDepthWidth = (uint32_t)width;
    opts.mvecDepthHeight = (uint32_t)height;
    opts.colorBufferFormat = (uint32_t)colorFormat;
    opts.mvecBufferFormat = (uint32_t)mvFormat;
    opts.depthBufferFormat = (uint32_t)depthFormat;
    // FG recomposition: we optionally provide UI color+alpha for a cleaner HUD output.
    opts.hudLessBufferFormat = (uint32_t)colorFormat;
    opts.uiBufferFormat = (uint32_t)colorFormat;  // RGBA8 for UI

    sl::Result r = slDLSSGSetOptions(vp, opts);
    if (r != sl::Result::eOk)
        std::fprintf(stderr, "[mcdlss] slDLSSGSetOptions mode=%d nfg=%d => %d\n",
                     mode, numFramesToGenerate, (int)r);
    return (jint)r;
}

// String NativeBridge.slDlssGGetStateNative()
// Returns a formatted string: "status=N numPresented=N maxFramesToGen=N vramMB=N vsync=N dynamicMFG=N"
JNIEXPORT jstring JNICALL
Java_net_kaiten_NativeBridge_slDlssGGetStateNative(JNIEnv* env, jclass) {
    sl::ViewportHandle vp(0);
    sl::DLSSGState state{};
    sl::Result r = slDLSSGGetState(vp, state, nullptr);
    char buf[256];
    if (r != sl::Result::eOk) {
        std::snprintf(buf, sizeof(buf), "query failed: result=%d", (int)r);
    } else {
        std::snprintf(buf, sizeof(buf),
            "status=%d numPresented=%u maxFramesToGen=%u vramMB=%llu vsync=%d dynamicMFG=%d",
            (int)state.status, state.numFramesActuallyPresented,
            state.numFramesToGenerateMax,
            (unsigned long long)(state.estimatedVRAMUsageInBytes / (1024*1024)),
            (int)state.bIsVsyncSupportAvailable, (int)state.bIsDynamicMFGSupported);
    }
    return env->NewStringUTF(buf);
}

/**
 * int NativeBridge.slDlssGEvaluateNative(frameIndex, cmdBuffer, width, height,
 *     handles[8], layouts[4], formats[4], consts[40])
 *
 * Tags HUD-less color + depth + MV + UI alpha resources for DLSS-G.
 * DLSS-G does NOT use slEvaluateFeature â€” it works through the interposer's
 * present interception. After tagging + setting constants here, the interposer
 * automatically injects interpolated frames during vkQueuePresentKHR.
 *
 * handles: [hudLessImg, hudLessView, depthImg, depthView, mvImg, mvView, uiColorImg, uiColorView]
 * layouts/formats order: hudLess, depth, mv, uiColor
 * consts: same layout as SR [0..15]=viewToClip [16..31]=clipToPrevClip jx jy mvsx mvsy near far fov aspect
 */
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slDlssGEvaluateNative(JNIEnv* env, jclass,
        jint frameIndex, jlong cmdBuffer, jint width, jint height,
        jlongArray jHandles, jintArray jLayouts, jintArray jFormats, jfloatArray jConsts) {
    jlong h[8]; env->GetLongArrayRegion(jHandles, 0, 8, h);
    jint lay[4]; env->GetIntArrayRegion(jLayouts, 0, 4, lay);
    jint fmt[4]; env->GetIntArrayRegion(jFormats, 0, 4, fmt);
    jfloat c[40]; env->GetFloatArrayRegion(jConsts, 0, 40, c);

    sl::ViewportHandle vp(0);
    sl::CommandBuffer* cmd = reinterpret_cast<sl::CommandBuffer*>(cmdBuffer);

    // --- Token: reuse SR's if already obtained this frame ---
    uint32_t fi = (uint32_t)frameIndex;
    sl::FrameToken* token = nullptr;
    if (fi == g_sharedTokenFrame) {
        token = g_sharedToken;  // SR already got it, reuse
    } else {
        sl::Result r = slGetNewFrameToken(token, &fi);
        if (r != sl::Result::eOk) {
            std::fprintf(stderr, "[mcdlss/FG] slGetNewFrameToken=%d\n", (int)r);
            return (jint)r;
        }
        g_sharedToken = token;
        g_sharedTokenFrame = fi;
        // Only set common constants if WE obtained the token (SR didn't set them yet).
        sl::Constants consts{};
        setRowMajor(consts.cameraViewToClip, &c[0]);
        setRowMajor(consts.clipToPrevClip, &c[16]);
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
        consts.cameraMotionIncluded = sl::Boolean::eTrue;
        consts.motionVectors3D = sl::Boolean::eFalse;
        consts.reset = sl::Boolean::eFalse;
        consts.cameraPinholeOffset = sl::float2(0.5f, 0.5f);
        r = slSetConstants(consts, *token, vp);
        if (r != sl::Result::eOk) {
            std::fprintf(stderr, "[mcdlss/FG] slSetConstants=%d\n", (int)r);
            return (jint)r;
        }
    }

    // --- Tag FG resources ---
    // Skip UI-alpha tag if the handle is null (pass-through mode).
    int tagCount = (h[6] == 0 && h[7] == 0) ? 3 : 4;

    sl::Resource hudLess = vkResource(h[0], h[1], lay[0], width, height, fmt[0]);
    sl::Resource depth   = vkResource(h[2], h[3], lay[1], width, height, fmt[1]);
    sl::Resource mvec    = vkResource(h[4], h[5], lay[2], width, height, fmt[2]);

    sl::Extent fullExt{0, 0, (uint32_t)width, (uint32_t)height};

    sl::Resource uiColor;
    sl::ResourceTag tags[4];
    tags[0] = sl::ResourceTag(&hudLess, sl::kBufferTypeHUDLessColor,  sl::ResourceLifecycle::eValidUntilPresent, &fullExt);
    tags[1] = sl::ResourceTag(&depth,   sl::kBufferTypeDepth,         sl::ResourceLifecycle::eValidUntilPresent, &fullExt);
    tags[2] = sl::ResourceTag(&mvec,    sl::kBufferTypeMotionVectors, sl::ResourceLifecycle::eValidUntilPresent, &fullExt);
    if (tagCount == 4) {
        uiColor = vkResource(h[6], h[7], lay[3], width, height, fmt[3]);
        tags[3] = sl::ResourceTag(&uiColor, sl::kBufferTypeUIColorAndAlpha, sl::ResourceLifecycle::eValidUntilPresent, &fullExt);
    }

    sl::Result r = slSetTag(vp, tags, tagCount, cmd);
    if (r != sl::Result::eOk) {
        std::fprintf(stderr, "[mcdlss/FG] slSetTag=%d\n", (int)r);
        return (jint)r;
    }

    // DLSS-G does NOT use slEvaluateFeature. The interposer automatically
    // generates + presents interpolated frames during vkQueuePresentKHR.
    // Returning eOk here means "tags + constants set successfully."
    return (jint)sl::Result::eOk;
}

} // extern "C"
