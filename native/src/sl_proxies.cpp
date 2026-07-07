// sl_proxies.cpp — SL interposer Vulkan proxy routing for Frame Generation present interception.
//
// In manual-hooking mode, DLSS-G only attaches to a swapchain created through SL's own
// swapchain/present proxies (ProgrammingGuideManualHooking.md sec4.2; sl_hooks.h mandatory
// hooks). VulkanMod normally calls vkCreateSwapchainKHR/vkQueuePresentKHR/etc. directly via
// LWJGL, bypassing SL entirely — that is why DLSS-G's numPresented stays 0. This file resolves
// those proxies from sl.interposer.dll (already loaded in-process by NativeBridge's preload)
// and forwards the mandatory swapchain/present calls through them so SL's present hook fires.
//
// All Vulkan handles are pointer-sized on x64 (both dispatchable and non-dispatchable), so — same
// approach as sl_dlss_sr.cpp — we avoid pulling in vulkan.h entirely and just forward raw
// addresses (LWJGL-allocated struct pointers, already in the correct Vulkan ABI layout) through
// function-pointer-typed proxies resolved at runtime.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <jni.h>
#include <cstdio>
#include <cstdint>

#include "mcdlss.h"

using VkHandle = void*;

typedef void*   (*PFN_vkGetDeviceProcAddr_)(VkHandle device, const char* pName);
typedef void*   (*PFN_vkGetInstanceProcAddr_)(VkHandle instance, const char* pName);
typedef int32_t (*PFN_vkCreateSwapchainKHR_)(VkHandle device, const void* pCreateInfo, const void* pAllocator, void* pSwapchain);
typedef void    (*PFN_vkDestroySwapchainKHR_)(VkHandle device, VkHandle swapchain, const void* pAllocator);
typedef int32_t (*PFN_vkGetSwapchainImagesKHR_)(VkHandle device, VkHandle swapchain, uint32_t* pCount, void* pImages);
typedef int32_t (*PFN_vkAcquireNextImageKHR_)(VkHandle device, VkHandle swapchain, uint64_t timeout, VkHandle semaphore, VkHandle fence, uint32_t* pImageIndex);
typedef int32_t (*PFN_vkQueuePresentKHR_)(VkHandle queue, const void* pPresentInfo);
typedef int32_t (*PFN_vkDeviceWaitIdle_)(VkHandle device);

namespace {
    PFN_vkGetDeviceProcAddr_     g_getDeviceProcAddr   = nullptr;
    PFN_vkGetInstanceProcAddr_   g_getInstanceProcAddr = nullptr;
    PFN_vkCreateSwapchainKHR_    g_createSwapchain     = nullptr;
    PFN_vkDestroySwapchainKHR_   g_destroySwapchain    = nullptr;
    PFN_vkGetSwapchainImagesKHR_ g_getSwapchainImages  = nullptr;
    PFN_vkAcquireNextImageKHR_   g_acquireNextImage    = nullptr;
    PFN_vkQueuePresentKHR_       g_queuePresent        = nullptr;
    PFN_vkDeviceWaitIdle_        g_deviceWaitIdle      = nullptr;

    void logProxy(const char* msg) {
        std::fprintf(stderr, "[MCDLSS/PROXY] %s\n", msg);
    }
}

extern "C" {

// int NativeBridge.slProxyInitNative(long device) — resolves the SL interposer's Vulkan
// swapchain/present proxies for this device. Returns the number of proxies resolved
// (6 = every mandatory hook available), or -1 if sl.interposer.dll isn't loaded in-process.
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slProxyInitNative(JNIEnv*, jclass, jlong device) {
    HMODULE mod = GetModuleHandleA("sl.interposer.dll");
    if (!mod) {
        logProxy("sl.interposer.dll not loaded in-process - cannot resolve proxies");
        return -1;
    }

    g_getDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr_>(GetProcAddress(mod, "vkGetDeviceProcAddr"));
    g_getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr_>(GetProcAddress(mod, "vkGetInstanceProcAddr"));
    if (!g_getDeviceProcAddr) {
        logProxy("vkGetDeviceProcAddr export missing from sl.interposer.dll");
        return -1;
    }

    VkHandle dev = reinterpret_cast<VkHandle>(device);
    int resolved = 0;

    g_createSwapchain = reinterpret_cast<PFN_vkCreateSwapchainKHR_>(g_getDeviceProcAddr(dev, "vkCreateSwapchainKHR"));
    resolved += (g_createSwapchain != nullptr);
    g_destroySwapchain = reinterpret_cast<PFN_vkDestroySwapchainKHR_>(g_getDeviceProcAddr(dev, "vkDestroySwapchainKHR"));
    resolved += (g_destroySwapchain != nullptr);
    g_getSwapchainImages = reinterpret_cast<PFN_vkGetSwapchainImagesKHR_>(g_getDeviceProcAddr(dev, "vkGetSwapchainImagesKHR"));
    resolved += (g_getSwapchainImages != nullptr);
    g_acquireNextImage = reinterpret_cast<PFN_vkAcquireNextImageKHR_>(g_getDeviceProcAddr(dev, "vkAcquireNextImageKHR"));
    resolved += (g_acquireNextImage != nullptr);
    g_queuePresent = reinterpret_cast<PFN_vkQueuePresentKHR_>(g_getDeviceProcAddr(dev, "vkQueuePresentKHR"));
    resolved += (g_queuePresent != nullptr);
    g_deviceWaitIdle = reinterpret_cast<PFN_vkDeviceWaitIdle_>(g_getDeviceProcAddr(dev, "vkDeviceWaitIdle"));
    resolved += (g_deviceWaitIdle != nullptr);

    char buf[192];
    std::snprintf(buf, sizeof(buf),
        "resolved %d/6 proxies (create=%d destroy=%d getImages=%d acquire=%d present=%d waitIdle=%d)",
        resolved, g_createSwapchain != nullptr, g_destroySwapchain != nullptr,
        g_getSwapchainImages != nullptr, g_acquireNextImage != nullptr,
        g_queuePresent != nullptr, g_deviceWaitIdle != nullptr);
    logProxy(buf);
    return (jint)resolved;
}

// int NativeBridge.slProxyCreateSwapchainKHR(device, pCreateInfoAddr, pSwapchainOutAddr)
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slProxyCreateSwapchainKHR(JNIEnv*, jclass, jlong device, jlong pCreateInfo, jlong pSwapchain) {
    if (!g_createSwapchain) return -3; // VK_ERROR_INITIALIZATION_FAILED
    return (jint)g_createSwapchain(reinterpret_cast<VkHandle>(device),
        reinterpret_cast<const void*>(pCreateInfo), nullptr, reinterpret_cast<void*>(pSwapchain));
}

// void NativeBridge.slProxyDestroySwapchainKHR(device, swapchain)
JNIEXPORT void JNICALL
Java_net_kaiten_NativeBridge_slProxyDestroySwapchainKHR(JNIEnv*, jclass, jlong device, jlong swapchain) {
    if (!g_destroySwapchain) return;
    g_destroySwapchain(reinterpret_cast<VkHandle>(device), reinterpret_cast<VkHandle>(swapchain), nullptr);
}

// int NativeBridge.slProxyGetSwapchainImagesKHR(device, swapchain, pCountAddr, pImagesAddr)
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slProxyGetSwapchainImagesKHR(JNIEnv*, jclass, jlong device, jlong swapchain, jlong pCount, jlong pImages) {
    if (!g_getSwapchainImages) return -3;
    return (jint)g_getSwapchainImages(reinterpret_cast<VkHandle>(device), reinterpret_cast<VkHandle>(swapchain),
        reinterpret_cast<uint32_t*>(pCount), reinterpret_cast<void*>(pImages));
}

// int NativeBridge.slProxyAcquireNextImageKHR(device, swapchain, timeout, semaphore, fence, pIndexAddr)
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slProxyAcquireNextImageKHR(JNIEnv*, jclass, jlong device, jlong swapchain,
        jlong timeout, jlong semaphore, jlong fence, jlong pIndex) {
    if (!g_acquireNextImage) return -3;
    return (jint)g_acquireNextImage(reinterpret_cast<VkHandle>(device), reinterpret_cast<VkHandle>(swapchain),
        (uint64_t)timeout, reinterpret_cast<VkHandle>(semaphore), reinterpret_cast<VkHandle>(fence),
        reinterpret_cast<uint32_t*>(pIndex));
}

// int NativeBridge.slProxyQueuePresentKHR(queue, pPresentInfoAddr)
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slProxyQueuePresentKHR(JNIEnv*, jclass, jlong queue, jlong pPresentInfo) {
    if (!g_queuePresent) return -3;
    return (jint)g_queuePresent(reinterpret_cast<VkHandle>(queue), reinterpret_cast<const void*>(pPresentInfo));
}

// int NativeBridge.slProxyDeviceWaitIdle(device)
JNIEXPORT jint JNICALL
Java_net_kaiten_NativeBridge_slProxyDeviceWaitIdle(JNIEnv*, jclass, jlong device) {
    if (!g_deviceWaitIdle) return -3;
    return (jint)g_deviceWaitIdle(reinterpret_cast<VkHandle>(device));
}

} // extern "C"
