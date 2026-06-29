package net.vulkanmod.dlss;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.GraphicsQueue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Gated, self-contained validation that DLSS Super Resolution actually evaluates on the GPU:
 * creates synthetic low-res color/depth/MV inputs + a native-res output, tags them, sets
 * constants, and runs slEvaluateFeature(kFeatureDLSS). A result of eOk means NGX ran the
 * upscale inference end-to-end. Cannot affect normal rendering (separate images + command
 * buffer). Enable with -Dmcdlss.evaltest=true.
 */
public final class DlssEvaluateValidator {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private DlssEvaluateValidator() {}

    public static void run() {
        final int outW = 1280, outH = 720;
        final int renderW = 640, renderH = 360;     // DLSS Performance = 0.5x
        final int VIEWPORT = 0, FRAME = 1, MODE = NativeBridge.DLSS_PERF;

        VulkanImage colorIn = null, colorOut = null, depth = null, mv = null;
        try {
            int imgUsage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT
                    | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            colorIn  = img(renderW, renderH, VK_FORMAT_R8G8B8A8_UNORM, imgUsage);
            colorOut = img(outW, outH, VK_FORMAT_R8G8B8A8_UNORM, imgUsage);
            depth    = img(renderW, renderH, VK_FORMAT_D32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
            mv       = img(renderW, renderH, VK_FORMAT_R16G16_SFLOAT, imgUsage);

            GraphicsQueue q = DeviceManager.getGraphicsQueue();
            CommandPool.CommandBuffer cb = q.beginCommands();
            VkCommandBuffer cmd = cb.getHandle();

            try (MemoryStack stack = stackPush()) {
                colorIn.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                colorOut.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_GENERAL);
                depth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                mv.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            // Constants: a real projection + identity clip-to-prev (no camera motion), no jitter.
            Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), (float) outW / outH, 0.05f, 1000f, true);
            float[] consts = new float[40];
            rowMajor(proj, consts, 0);          // cameraViewToClip
            identityRowMajor(consts, 16);       // clipToPrevClip
            consts[32] = 0f; consts[33] = 0f;   // jitter
            consts[34] = 1f; consts[35] = 1f;   // mvecScale
            consts[36] = 0.05f; consts[37] = 1000f; consts[38] = (float) Math.toRadians(70.0); consts[39] = (float) outW / outH;

            long[] handles = {
                    colorIn.getId(), colorIn.getImageView(),
                    colorOut.getId(), colorOut.getImageView(),
                    depth.getId(), depth.getImageView(),
                    mv.getId(), mv.getImageView()
            };
            int[] layouts = { VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
            int[] formats = { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, VK_FORMAT_R16G16_SFLOAT };

            int r = NativeBridge.slDlssEvaluateNative(VIEWPORT, FRAME, cmd.address(), MODE,
                    outW, outH, renderW, renderH, handles, layouts, formats, consts);

            long fence = q.submitCommands(cb);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, Long.MAX_VALUE);

            if (r == 0) {
                LOGGER.info("[DLSS EvalTest] PASS — slEvaluateFeature(DLSS) ran: {}x{} -> {}x{} (Performance)",
                        renderW, renderH, outW, outH);
            } else {
                String name;
                try { name = NativeBridge.slResultNameNative(r); } catch (Throwable t) { name = "code=" + r; }
                LOGGER.error("[DLSS EvalTest] FAIL — evaluate returned {} (see [mcdlss] stderr for the failing step)", name);
            }
        } catch (Throwable t) {
            LOGGER.error("[DLSS EvalTest] FAIL — {}", t.toString());
        } finally {
            free(colorIn); free(colorOut); free(depth); free(mv);
        }
    }

    private static VulkanImage img(int w, int h, int format, int usage) {
        return VulkanImage.builder(w, h).setFormat(format).setUsage(usage)
                .setLinearFiltering(false).setClamp(true).createVulkanImage();
    }

    private static void free(VulkanImage i) { if (i != null) try { i.free(); } catch (Throwable ignored) {} }

    private static void rowMajor(Matrix4f m, float[] out, int off) {
        // JOML is column-major (m.get fills column-major); transpose to row-major for Streamline.
        out[off + 0] = m.m00(); out[off + 1] = m.m10(); out[off + 2] = m.m20(); out[off + 3] = m.m30();
        out[off + 4] = m.m01(); out[off + 5] = m.m11(); out[off + 6] = m.m21(); out[off + 7] = m.m31();
        out[off + 8] = m.m02(); out[off + 9] = m.m12(); out[off + 10] = m.m22(); out[off + 11] = m.m32();
        out[off + 12] = m.m03(); out[off + 13] = m.m13(); out[off + 14] = m.m23(); out[off + 15] = m.m33();
    }

    private static void identityRowMajor(float[] out, int off) {
        for (int i = 0; i < 16; i++) out[off + i] = 0f;
        out[off] = out[off + 5] = out[off + 10] = out[off + 15] = 1f;
    }
}
