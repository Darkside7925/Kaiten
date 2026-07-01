package net.kaiten;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * DLSS Frame Generation (DLSS-G / Multi-Frame Generation).
 *
 * <p>Phase 5: AI-interpolates frames between fully-rendered frames.
 * On Blackwell GPUs (RTX 50-series) this supports up to 4x (MFG).
 *
 * <p>Requirements (all gated; gracefully falls back to passthrough):
 * <ul>
 *   <li>Reflex must be enabled (mandatory dependency)</li>
 *   <li>HAGS must be enabled in Windows</li>
 *   <li>VSync must be off</li>
 *   <li>DLSS-G must be supported by the GPU</li>
 * </ul>
 *
 * <p>Architecture:
 * <pre>
 *   Render scene (no UI) â†’ HUD-less color buffer
 *   Render UI separately   â†’ UI color+alpha buffer
 *   DLSS-G evaluate:        tags HUD-less color + depth + MV + UI alpha
 *                          â†’ SL writes interpolated frame to swapchain backbuffer
 *   Present:                SL owns present timing when FG is active
 * </pre>
 *
 * <p>Gated by {@code -Dmcdlss.fg} system property (default OFF).
 */
public final class DlssFrameGeneration {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private DlssFrameGeneration() {}

    /** Master switch. Default OFF until proven stable. */
    public static boolean enabled = Boolean.getBoolean("mcdlss.fg");
    private static boolean failed = false;

    // Pre-allocated dummy resources reused across frames.
    private static VulkanImage mvZero;           // RG16F: zero MV fallback (allocated once, lazily)
    private static int width, height;

    private static final float[] consts = new float[40];
    private static long framesRun = 0;

    /**
     * Run DLSS Frame Generation on the rendered frame.
     * Called after the world+UI are rendered but before present.
     *
     * <p>For the initial integration: uses the swapchain color directly as
     * HUD-less input (UI is baked in â€” cosmetic, not blocking). SL writes
     * the interpolated frame back into the swapchain backbuffer via the
     * interposer, which also intercepts the present call.
     *
     * @param cmd       the command buffer
     * @param color     the swapchain color image (RGBA8/BGRA8) â€” used as HUD-less input
     * @param depth     the swapchain depth image (D32)
     * @param mv        motion-vector buffer (RG16F), or null for zero-fill fallback
     * @param w,h       frame dimensions
     */
    public static void render(VkCommandBuffer cmd, VulkanImage color, VulkanImage depth,
            VulkanImage mv, int w, int h) {
        if (!enabled || failed) return;
        if (!NativeBridge.frameGenSupported || !NativeBridge.frameGenConfigured) return;
        if (!NativeBridge.isStreamlineInitialized()) return;

        try {
            // Lazy-allocate zero MV fallback on first frame.
            if (mvZero == null || width != w || height != h) {
                ensureMvZero(w, h);
            }

            // Transition inputs to shader-read for tagging.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                color.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                depth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            VulkanImage mvImg = (mv != null) ? mv : mvZero;
            if (mv == null) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    mvZero.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
            }

            DlssFrameState.fillSrConstants(consts, 0f, 0f, 1f, 1f);

            // Tag: HUD-less color (swapchain color), depth, MV. No UI alpha buffer
            // for the initial test â€” UI recomposition is deferred to a later phase.
            long[] handles = {
                    color.getId(), color.getImageView(),     // HUD-less color
                    depth.getId(), depth.getImageView(),     // depth
                    mvImg.getId(), mvImg.getImageView(),     // motion vectors
                    0L, 0L                                   // UI alpha: null (pass-through)
            };
            int[] layouts = {
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,  // hudLess
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,  // depth
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,  // mv
                    0                                           // uiColor (unused)
            };
            int[] formats = {
                    color.format, depth.format,
                    (mv != null) ? mv.format : VK_FORMAT_R16G16_SFLOAT,
                    0  // uiColor (unused)
            };

            int r = NativeBridge.slDlssGEvaluateNative(
                    (int) DlssFrameState.frameCounter(), cmd.address(), w, h,
                    handles, layouts, formats, consts);

            if (r != 0) {
                NativeBridge.frameGenActive = false;
                if (framesRun == 0) {
                    LOGGER.error("[DLSS-G] evaluate failed (result={}) â€” FG disabled.",
                            NativeBridge.slResultNameNative(r));
                }
                failed = true;
                return;
            }

            NativeBridge.frameGenActive = true;

            if (framesRun++ == 0) LOGGER.info("[DLSS-G] Frame Generation running, {}x{}", w, h);
            else if (framesRun % 300 == 0) {
                try {
                    String state = NativeBridge.slDlssGGetStateNative();
                    LOGGER.info("[DLSS-G] {} frames, state: {}", framesRun, state);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("[DLSS-G] in-frame FG failed â€” disabling: {}", t.toString());
        }
    }

    /** Change the FG multiplier at runtime (e.g., from UI settings). Best-effort. */
    public static void setMultiplier(int numFramesToGenerate) {
        if (!enabled || failed || !NativeBridge.frameGenConfigured) return;
        if (numFramesToGenerate < 1 || numFramesToGenerate > NativeBridge.frameGenMaxMultiplier) return;
        try {
            int flags = NativeBridge.DLSSG_FLAG_RETAIN_RESOURCES;
            int r = NativeBridge.slDlssGSetOptionsNative(NativeBridge.DLSSG_ON,
                    numFramesToGenerate, flags, width, height,
                    /* VK_FORMAT_R8G8B8A8_UNORM */ 37,
                    /* VK_FORMAT_R16G16_SFLOAT */ 83,
                    /* VK_FORMAT_D32_SFLOAT */ 126);
            if (r == 0) {
                NativeBridge.frameGenCurrentMultiplier = numFramesToGenerate;
                LOGGER.info("[DLSS-G] Multiplier changed to {}x", numFramesToGenerate + 1);
            }
        } catch (Throwable t) {
            LOGGER.warn("[DLSS-G] setMultiplier error: {}", t.toString());
        }
    }

    /** Disable FG at runtime. Idempotent. */
    public static void disable() {
        if (!enabled || !NativeBridge.frameGenConfigured) return;
        try {
            NativeBridge.slDlssGSetOptionsNative(NativeBridge.DLSSG_OFF, 0, 0,
                    width, height, 37, 83, 126);
            NativeBridge.frameGenActive = false;
            LOGGER.info("[DLSS-G] Disabled.");
        } catch (Throwable ignored) {}
    }

    private static void ensureMvZero(int w, int h) {
        if (mvZero != null) mvZero.free();
        width = w; height = h;
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        mvZero = VulkanImage.builder(w, h)
                .setFormat(VK_FORMAT_R16G16_SFLOAT)
                .setUsage(usage)
                .setLinearFiltering(false)
                .setClamp(true)
                .createVulkanImage();
        LOGGER.info("[DLSS-G] Allocated zero-MV fallback: {}x{}", w, h);
    }
}
