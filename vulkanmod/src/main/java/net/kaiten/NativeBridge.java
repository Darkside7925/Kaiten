package net.kaiten;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JNI bridge to {@code mcdlss_native.dll} â€” the C++ glue that owns the NVIDIA Streamline
 * lifecycle (slInit / feature checks / tagging / evaluate) on top of VulkanMod's Vulkan device.
 *
 * <p>Phase 0 scope: prove the native library loads inside Minecraft and a JNI round-trip works
 * ({@link #hello(String)} / {@link #abiVersion()}) BEFORE any Streamline code is involved.
 * Streamline init and feature queries are added in Phase 1.
 *
 * <p>Loading is best-effort: any failure (missing DLL, non-Windows, load error) is caught and
 * leaves {@link #isLoaded()} false, so the game continues as plain VulkanMod (graceful fallback).
 */
public final class NativeBridge {
    public static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");

    /** Must match MCDLSS_ABI_VERSION in native/include/mcdlss.h. Bumped on any JNI signature change. */
    public static final int EXPECTED_ABI_VERSION = 4;

    private static final String LIB_NAME = "mcdlss_native";

    // Streamline feature ids â€” must match sl_core_types.h.
    public static final int FEATURE_DLSS   = 0;     // Super Resolution
    public static final int FEATURE_REFLEX = 3;
    public static final int FEATURE_PCL    = 4;     // PC Latency
    public static final int FEATURE_DLSS_G = 1000;  // Frame Generation

    private static boolean loaded = false;
    private static String loadError = null;
    private static Path nativeDir = null;

    private static boolean streamlineInitialized = false;
    // Per-feature support, populated by reportFeatures().
    public static boolean dlssSupported = false;
    public static boolean frameGenSupported = false;
    public static boolean reflexSupported = false;

    private NativeBridge() {}

    // --- Phase 0 native methods (no Streamline dependency) ---

    /** Round-trips a string through native code; returns a native-built banner string. */
    public static native String hello(String from);

    /** Returns the native library's ABI version, validated against {@link #EXPECTED_ABI_VERSION}. */
    public static native int abiVersion();

    // --- Phase 1 native methods (Streamline) â€” only present when the DLL is built with Streamline ---

    /** slInit against Vulkan. pluginDir = folder holding sl.*.dll. Returns sl::Result (0 = eOk). */
    public static native int slInitNative(String pluginDir, int logLevel);

    /** slIsFeatureSupported for the given feature + VkPhysicalDevice handle (0 = no adapter). 0 = supported. */
    public static native int slIsFeatureSupportedNative(int feature, long vkPhysicalDevice);

    /** Human-readable slGetFeatureRequirements summary (driver/os/flags/queues) for the feature. */
    public static native String slFeatureRequirementsNative(int feature);

    /** Maps an sl::Result code to its enum name. */
    public static native String slResultNameNative(int code);

    /** slShutdown. Returns sl::Result (0 = eOk). */
    public static native int slShutdownNative();

    // --- Phase 3 (DLSS-SR) native methods ---

    /** slSetVulkanInfo â€” hand SL the Vulkan device (manual hooking). Returns sl::Result (0 = eOk). */
    public static native int slSetVulkanInfoNative(long instance, long physicalDevice, long device,
                                                   int gfxFamily, int gfxIndex, int cmpFamily, int cmpIndex);

    /** slDLSSGetOptimalSettings for the given output size + sl::DLSSMode; returns a formatted summary. */
    public static native String slDlssOptimalSettingsNative(int outputWidth, int outputHeight, int mode);

    /** Newline-joined Vulkan device extensions DLSS requires (from slGetFeatureRequirements). */
    public static native String slDlssDeviceExtensionsNative();
    /** Newline-joined Vulkan instance extensions DLSS requires. */
    public static native String slDlssInstanceExtensionsNative();
    /** Newline-joined Vulkan 1.2/1.3 feature names DLSS requires (diagnostic). */
    public static native String slDlssFeaturesNative();

    /** Tags SR resources, sets options+constants, runs slEvaluateFeature(DLSS). Returns sl::Result. */
    public static native int slDlssEvaluateNative(int viewport, int frameIndex, long cmdBuffer, int mode,
            int outW, int outH, int renderW, int renderH,
            long[] handles, int[] layouts, int[] formats, float[] consts);

    /** Device extensions DLSS needs, for injection into VulkanMod's vkCreateDevice. Empty if unavailable. */
    public static synchronized java.util.List<String> dlssDeviceExtensions() {
        if (!streamlineInitialized) return java.util.List.of();
        try {
            String s = slDlssDeviceExtensionsNative();
            if (s == null || s.isBlank()) return java.util.List.of();
            return java.util.List.of(s.split("\n"));
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    /** Instance extensions DLSS needs, for injection into vkCreateInstance. */
    public static synchronized java.util.List<String> dlssInstanceExtensions() {
        if (!streamlineInitialized) return java.util.List.of();
        try {
            String s = slDlssInstanceExtensionsNative();
            if (s == null || s.isBlank()) return java.util.List.of();
            return java.util.List.of(s.split("\n"));
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    // sl::DLSSMode values.
    public static final int DLSS_OFF = 0, DLSS_PERF = 1, DLSS_BALANCED = 2, DLSS_QUALITY = 3,
            DLSS_ULTRA_PERF = 4, DLSS_ULTRA_QUALITY = 5, DLSS_DLAA = 6;

    private static boolean vulkanInfoSet = false;

    // --- Phase 4 (Reflex) native methods ---
    public static native int slReflexSetOptionsNative(int mode, int frameLimitUs);
    public static native int slReflexSleepNative(int frameIndex);
    public static native int slPclMarkerNative(int marker, int frameIndex);
    public static native String slReflexStateNative();

    // --- Phase 5 (DLSS-G / Frame Generation) native methods ---

    /** slDLSSGSetOptions: configure FG mode, multiplier, formats. Returns sl::Result (0 = eOk). */
    public static native int slDlssGSetOptionsNative(int mode, int numFramesToGenerate, int flags,
            int width, int height, int colorFormat, int mvFormat, int depthFormat);

    /** slDLSSGGetState: returns a formatted string with FG state (status, max multiplier, VRAM, etc.). */
    public static native String slDlssGGetStateNative();

    /**
     * Tags HUD-less color + depth + MV + UI alpha, then slEvaluateFeature(DLSS-G).
     * handles: [hudLessImg, hudLessView, depthImg, depthView, mvImg, mvView, uiColorImg, uiColorView]
     * layouts/formats order: hudLess, depth, mv, uiColor
     * consts: same 40-float layout as SR
     */
    public static native int slDlssGEvaluateNative(int frameIndex, long cmdBuffer, int width, int height,
            long[] handles, int[] layouts, int[] formats, float[] consts);

    // --- M2 (FG present interception) native methods ---
    // In manual-hooking mode, DLSS-G only attaches to a swapchain created through SL's own
    // proxies (see sl_proxies.cpp). These wrap the mandatory hooks from sl_hooks.h, taking raw
    // addresses of LWJGL-allocated Vulkan structs so the native side never needs vulkan.h.

    /** Resolves SL's Vulkan swapchain/present proxies for this device. Returns proxies resolved (6 = all). */
    public static native int slProxyInitNative(long device);
    public static native int slProxyCreateSwapchainKHR(long device, long pCreateInfoAddr, long pSwapchainAddr);
    public static native void slProxyDestroySwapchainKHR(long device, long swapchain);
    public static native int slProxyGetSwapchainImagesKHR(long device, long swapchain, long pCountAddr, long pImagesAddr);
    public static native int slProxyAcquireNextImageKHR(long device, long swapchain, long timeout, long semaphore, long fence, long pIndexAddr);
    public static native int slProxyQueuePresentKHR(long queue, long pPresentInfoAddr);
    public static native int slProxyDeviceWaitIdle(long device);

    /** True once SL's swapchain/present proxies are resolved and FG present-interception is wired in. */
    public static volatile boolean useSlProxies = false;

    /**
     * Resolves SL's Vulkan proxies for {@code device}. Gated on FG being supported and
     * {@code -Dmcdlss.fg=true} — without it, DLSS-G never attaches to our swapchain and there's
     * no reason to route present calls through SL. Best-effort: failure just leaves
     * {@link #useSlProxies} false and callers fall back to the direct LWJGL Vulkan calls.
     */
    public static synchronized void setupSlProxies(long device) {
        useSlProxies = false;
        if (!frameGenSupported || !Boolean.getBoolean("mcdlss.fg")) return;
        try {
            int resolved = slProxyInitNative(device);
            useSlProxies = (resolved == 6);
            LOGGER.info("SL interposer proxies: {}/6 resolved{}", Math.max(resolved, 0),
                    useSlProxies ? " - routing swapchain/present through SL" : " - falling back to direct Vulkan calls");
        } catch (Throwable t) {
            LOGGER.warn("SL proxy init error: {}", t.toString());
        }
    }

    // sl::DLSSGMode values.
    public static final int DLSSG_OFF = 0, DLSSG_ON = 1, DLSSG_AUTO = 2, DLSSG_DYNAMIC = 3;
    // DLSSGFlags
    public static final int DLSSG_FLAG_SHOW_ONLY_INTERPOLATED = 1 << 0;
    public static final int DLSSG_FLAG_DYNAMIC_RES = 1 << 1;
    public static final int DLSSG_FLAG_RETAIN_RESOURCES = 1 << 3;

    // sl::ReflexMode + sl::PCLMarker values.
    public static final int REFLEX_OFF = 0, REFLEX_LOW_LATENCY = 1, REFLEX_LOW_LATENCY_BOOST = 2;
    public static final int PCL_SIM_START = 0, PCL_SIM_END = 1, PCL_RENDER_SUBMIT_START = 2,
            PCL_RENDER_SUBMIT_END = 3, PCL_PRESENT_START = 4, PCL_PRESENT_END = 5, PCL_LATENCY_PING = 8;

    private static boolean reflexEnabled = false;

    /** Enable Reflex low-latency (after device set). Logs the resulting state. Best-effort. */
    public static synchronized void setupReflex(int mode) {
        if (!vulkanInfoSet || !reflexSupported) return;
        try {
            int r = slReflexSetOptionsNative(mode, 0);
            if (r == 0) {
                reflexEnabled = (mode != REFLEX_OFF);
                LOGGER.info("Reflex options set (mode={}); state: {}", mode, slReflexStateNative());
            } else {
                LOGGER.warn("Reflex setOptions failed: {}", resultName(r));
            }
        } catch (Throwable t) {
            LOGGER.warn("Reflex setup error: {}", t.toString());
        }
    }

    // --- Phase 5: Frame Generation state ---

    public static boolean frameGenConfigured = false;
    public static boolean frameGenActive = false;
    public static int frameGenMaxMultiplier = 0;      // max numFramesToGenerate supported (1=2x, 3=4x)
    public static int frameGenCurrentMultiplier = 0;   // currently configured multiplier

    /**
     * Initialize DLSS Frame Generation. Must be called after the device is set + Reflex is enabled.
     * Queries the GPU's maximum FG multiplier and logs it.
     */
    public static synchronized void setupFrameGeneration(int width, int height,
            int colorFormat, int mvFormat, int depthFormat) {
        if (!vulkanInfoSet || !frameGenSupported) return;
        try {
            // Query state first to discover max multiplier.
            String stateStr = slDlssGGetStateNative();
            LOGGER.info("DLSS-G initial state: {}", stateStr);

            // Parse maxFramesToGen from the state string.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("maxFramesToGen=(\\d+)")
                    .matcher(stateStr);
            if (m.find()) {
                frameGenMaxMultiplier = Integer.parseInt(m.group(1));
                LOGGER.info("DLSS-G max multiplier: {}x ({} frames to generate)",
                        frameGenMaxMultiplier + 1, frameGenMaxMultiplier);
            }

            // Start with 2x (numFramesToGenerate=1) as the safe default.
            frameGenCurrentMultiplier = Math.min(1, frameGenMaxMultiplier);  // default 2x
            int flags = DLSSG_FLAG_RETAIN_RESOURCES;
            int r = slDlssGSetOptionsNative(DLSSG_ON, frameGenCurrentMultiplier, flags,
                    width, height, colorFormat, mvFormat, depthFormat);
            if (r == 0) {
                frameGenConfigured = true;
                LOGGER.info("DLSS-G configured: {}x, {}x{}",
                        frameGenCurrentMultiplier + 1, width, height);
            } else {
                LOGGER.warn("DLSS-G setOptions failed: {}", resultName(r));
            }
        } catch (Throwable t) {
            LOGGER.warn("DLSS-G setup error: {}", t.toString());
        }
    }

    /** Reflex sleep + simulation-start marker â€” call at the very start of a frame. Best-effort. */
    public static void reflexFrameStart(int frameIndex) {
        if (!reflexEnabled) return;
        try {
            slReflexSleepNative(frameIndex);
            slPclMarkerNative(PCL_SIM_START, frameIndex);
        } catch (Throwable ignored) {}
    }

    /** Place a PCL latency marker. Best-effort. */
    public static void reflexMarker(int marker, int frameIndex) {
        if (!reflexEnabled) return;
        try { slPclMarkerNative(marker, frameIndex); } catch (Throwable ignored) {}
    }

    /**
     * Attempts to load {@code mcdlss_native.dll}. Idempotent. Search order:
     * <ol>
     *   <li>{@code -Dmcdlss.native.path=<abs path to dll>} (dev override)</li>
     *   <li>{@code <gameDir>/mcdlss/mcdlss_native.dll}</li>
     *   <li>{@code <run>/../native/build/Release/mcdlss_native.dll} and Debug (dev-loop convenience)</li>
     *   <li>{@code System.loadLibrary(LIB_NAME)} via {@code java.library.path}</li>
     * </ol>
     */
    public static synchronized boolean load() {
        if (loaded) return true;
        if (loadError != null) return false; // already failed once; don't spam

        try {
            Path explicit = locate();
            if (explicit != null) {
                nativeDir = explicit.toAbsolutePath().getParent();
                // Preload Streamline's interposer (if staged next to us) so the OS loader can
                // satisfy mcdlss_native.dll's import of the SL core API. Best-effort: a Phase 0
                // (Streamline-less) DLL has no such import and this is simply skipped.
                preloadIfPresent(nativeDir, "sl.interposer.dll");
                System.load(explicit.toAbsolutePath().toString());
                LOGGER.info("Loaded native library: {}", explicit.toAbsolutePath());
            } else {
                System.loadLibrary(LIB_NAME);
                LOGGER.info("Loaded native library via java.library.path: {}", LIB_NAME);
            }
        } catch (Throwable t) {
            loadError = t.toString();
            LOGGER.warn("DLSS native library not loaded â€” continuing as plain VulkanMod. Reason: {}", loadError);
            return false;
        }

        // Validate ABI before trusting any other native call.
        try {
            int abi = abiVersion();
            if (abi != EXPECTED_ABI_VERSION) {
                loadError = "ABI mismatch: native=" + abi + " expected=" + EXPECTED_ABI_VERSION;
                LOGGER.error("DLSS native {} â€” disabling DLSS.", loadError);
                return false;
            }
            loaded = true;
            return true;
        } catch (Throwable t) {
            loadError = "ABI check failed: " + t;
            LOGGER.error("DLSS native {} â€” disabling DLSS.", loadError);
            return false;
        }
    }

    private static Path locate() {
        // 1) explicit dev override
        String prop = System.getProperty("mcdlss.native.path");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop);
            if (Files.isRegularFile(p)) return p;
            LOGGER.warn("mcdlss.native.path set but not a file: {}", prop);
        }

        String dll = mapLibraryName();
        Path gameDir = FabricLoader.getInstance().getGameDir();

        List<Path> candidates = List.of(
                gameDir.resolve("mcdlss").resolve(dll),
                gameDir.resolve(dll),
                // dev-loop: built straight from the CMake tree alongside the fork
                gameDir.resolve("../native/build/Release").resolve(dll).normalize(),
                gameDir.resolve("../native/build/Debug").resolve(dll).normalize(),
                gameDir.resolve("../../native/build/Release").resolve(dll).normalize()
        );
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) return c;
        }
        return null;
    }

    private static String mapLibraryName() {
        // On Windows: "mcdlss_native.dll"; keep cross-platform-friendly for a future Linux SR path.
        return System.mapLibraryName(LIB_NAME);
    }

    private static void preloadIfPresent(Path dir, String dll) {
        if (dir == null) return;
        Path p = dir.resolve(dll);
        if (Files.isRegularFile(p)) {
            try {
                System.load(p.toAbsolutePath().toString());
                LOGGER.info("Preloaded {}", p.getFileName());
            } catch (Throwable t) {
                LOGGER.warn("Preload of {} failed: {}", dll, t.toString());
            }
        }
    }

    // --- Phase 1: Streamline lifecycle (all best-effort; failure â†’ plain VulkanMod) ---

    /** Initializes Streamline (slInit). Call early, before the Vulkan device is created. */
    public static synchronized void initStreamline() {
        if (!loaded || streamlineInitialized) return;
        try {
            String pluginDir = (nativeDir != null) ? nativeDir.toString() : "";
            int r = slInitNative(pluginDir, /* LogLevel.eDefault */ 1);
            if (r == 0) {
                streamlineInitialized = true;
                LOGGER.info("Streamline initialized (SDK 2.12.0, Vulkan).");
                try {
                    LOGGER.info("DLSS requires VK device extensions: {}", slDlssDeviceExtensionsNative().replace("\n", ", "));
                    LOGGER.info("DLSS requires VK instance extensions: {}", slDlssInstanceExtensionsNative().replace("\n", ", "));
                    LOGGER.info("DLSS requires VK 1.2/1.3 features: {}", slDlssFeaturesNative().replace("\n", ", "));
                } catch (Throwable ignored) {}
            } else {
                LOGGER.warn("Streamline init failed: {} â€” DLSS disabled.", resultName(r));
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("Native library built without Streamline â€” DLSS disabled.");
        } catch (Throwable t) {
            LOGGER.warn("Streamline init error: {} â€” DLSS disabled.", t.toString());
        }
    }

    /**
     * Queries and logs which DLSS features the current adapter supports.
     * Call after the Vulkan device exists, passing VkPhysicalDevice.address().
     */
    public static synchronized void reportFeatures(long vkPhysicalDevice) {
        if (!streamlineInitialized) return;
        try {
            dlssSupported     = checkFeature("DLSS Super Resolution", FEATURE_DLSS,   vkPhysicalDevice);
            reflexSupported   = checkFeature("Reflex",                FEATURE_REFLEX, vkPhysicalDevice);
            frameGenSupported = checkFeature("DLSS Frame Generation", FEATURE_DLSS_G, vkPhysicalDevice);
            checkFeature("PC Latency (PCL)", FEATURE_PCL, vkPhysicalDevice);
            LOGGER.info("DLSS feature report â€” Super Resolution: {}, Frame Generation: {}, Reflex: {}",
                    yesNo(dlssSupported), yesNo(frameGenSupported), yesNo(reflexSupported));
        } catch (Throwable t) {
            LOGGER.warn("DLSS feature query failed: {}", t.toString());
        }
    }

    /**
     * Hand Streamline the Vulkan device (required before any DLSS feature function) and log
     * the optimal render resolutions per quality preset. Called once after device creation.
     */
    public static synchronized void setupDlssDevice(long instance, long physicalDevice, long device,
                                                    int gfxFamily, int gfxIndex, int cmpFamily, int cmpIndex) {
        if (!streamlineInitialized || vulkanInfoSet) return;
        try {
            int r = slSetVulkanInfoNative(instance, physicalDevice, device, gfxFamily, gfxIndex, cmpFamily, cmpIndex);
            if (r != 0) {
                LOGGER.warn("slSetVulkanInfo failed: {} â€” DLSS evaluate unavailable.", resultName(r));
                return;
            }
            vulkanInfoSet = true;
            LOGGER.info("Streamline Vulkan device set (manual hooking).");
        } catch (Throwable t) {
            LOGGER.warn("slSetVulkanInfo error: {}", t.toString());
            return;
        }

        // M2: resolve SL's swapchain/present proxies so DLSS-G's present hook can attach.
        setupSlProxies(device);

        // Phase 4: enable Reflex low-latency (mandatory dependency for Frame Generation).
        setupReflex(REFLEX_LOW_LATENCY);

        // Phase 5: initialize Frame Generation if supported.
        if (frameGenSupported) {
            com.mojang.blaze3d.platform.Window w = net.minecraft.client.Minecraft.getInstance().getWindow();
            // FG uses RGBA8 for color, RG16F for MV, D32_SFLOAT for depth.
            // These formats are queried during setup; use the Vulkan format constants.
            setupFrameGeneration(w.getWidth(), w.getHeight(),
                    /* VK_FORMAT_R8G8B8A8_UNORM */ 37,
                    /* VK_FORMAT_R16G16_SFLOAT */ 83,
                    /* VK_FORMAT_D32_SFLOAT */ 126);
        }

        if (!dlssSupported) return;
        try {
            com.mojang.blaze3d.platform.Window w = net.minecraft.client.Minecraft.getInstance().getWindow();
            int ow = w.getWidth(), oh = w.getHeight();
            LOGGER.info("DLSS optimal render resolutions for output {}x{}:", ow, oh);
            logDlssMode("DLAA       ", DLSS_DLAA, ow, oh);
            logDlssMode("Quality    ", DLSS_QUALITY, ow, oh);
            logDlssMode("Balanced   ", DLSS_BALANCED, ow, oh);
            logDlssMode("Performance", DLSS_PERF, ow, oh);
            logDlssMode("UltraPerf  ", DLSS_ULTRA_PERF, ow, oh);
        } catch (Throwable t) {
            LOGGER.warn("DLSS optimal-settings query failed: {}", t.toString());
        }
    }

    private static void logDlssMode(String label, int mode, int ow, int oh) {
        try {
            LOGGER.info("  {} -> {}", label, slDlssOptimalSettingsNative(ow, oh, mode));
        } catch (Throwable t) {
            LOGGER.warn("  {} -> query error: {}", label, t.toString());
        }
    }

    private static boolean checkFeature(String name, int feature, long physDev) {
        int r = slIsFeatureSupportedNative(feature, physDev);
        boolean ok = (r == 0);
        String req = requirements(feature);
        if (ok) LOGGER.info("  {}: SUPPORTED   [{}]", name, req);
        else    LOGGER.info("  {}: unavailable ({})   [{}]", name, resultName(r), req);
        return ok;
    }

    /** Shuts Streamline down (slShutdown). Call on game shutdown. */
    public static synchronized void shutdownStreamline() {
        if (!streamlineInitialized) return;
        try {
            int r = slShutdownNative();
            LOGGER.info("Streamline shutdown: {}", resultName(r));
        } catch (Throwable t) {
            LOGGER.warn("Streamline shutdown error: {}", t.toString());
        } finally {
            streamlineInitialized = false;
        }
    }

    private static String resultName(int code) {
        try { return slResultNameNative(code); } catch (Throwable t) { return "code=" + code; }
    }

    private static String requirements(int feature) {
        try { return slFeatureRequirementsNative(feature); } catch (Throwable t) { return "n/a"; }
    }

    private static String yesNo(boolean b) { return b ? "yes" : "no"; }

    public static boolean isLoaded() { return loaded; }

    public static boolean isStreamlineInitialized() { return streamlineInitialized; }

    public static String getLoadError() { return loadError; }
}
