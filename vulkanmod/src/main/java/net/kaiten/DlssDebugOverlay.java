package net.kaiten;

import com.google.gson.JsonObject;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Phase 2 debug overlay: draws the camera-only motion-vector FIELD as a full-screen color
 * visualization (R/G = motion x/y around neutral grey). Flat-depth for now (no depth-buffer
 * sampling) â€” proves matrix capture + GPU reprojection visually: smooth gradient when the
 * camera moves, flat grey when still.
 *
 * Toggle with {@code -Dmcdlss.overlay=true} (or flip {@link #enabled} at runtime).
 */
public final class DlssDebugOverlay {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private DlssDebugOverlay() {}

    public static boolean enabled = Boolean.getBoolean("mcdlss.overlay");

    private static GraphicsPipeline pipeline;
    private static boolean failed = false;

    public static void render() {
        if (!enabled || failed) return;
        if (!DlssFrameState.hasPreviousFrame()) return;

        try {
            Renderer renderer = Renderer.getInstance();
            if (pipeline == null) {
                pipeline = createPipeline();
                LOGGER.info("[DLSS Overlay] pipeline created");
            }

            boolean prevDepthTest = VRenderSystem.depthTest;
            boolean prevDepthMask = VRenderSystem.depthMask;
            VRenderSystem.depthTest = false;
            VRenderSystem.depthMask = false;
            VRenderSystem.disableCull();
            VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);

            renderer.bindGraphicsPipeline(pipeline);
            renderer.uploadAndBindUBOs(pipeline);

            VkCommandBuffer cmd = Renderer.getCommandBuffer();
            VK11.vkCmdDraw(cmd, 3, 1, 0, 0);

            VRenderSystem.enableCull();
            VRenderSystem.depthTest = prevDepthTest;
            VRenderSystem.depthMask = prevDepthMask;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("[DLSS Overlay] draw failed â€” disabling overlay: {}", t.toString());
        }
    }

    private static GraphicsPipeline createPipeline() {
        Pipeline.Builder b = new Pipeline.Builder(CustomVertexFormat.NONE, "mv_debug");
        final String path = ShaderLoadUtil.resolveShaderPath("dlss/mv_debug");
        JsonObject config = ShaderLoadUtil.getJsonConfig(path, "mv_debug");
        PipelineConfig pc = PipelineConfig.fromJson("mv_debug", config);
        b.applyConfig(pc);
        b.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER,
                ShaderLoadUtil.loadShader(path, "%s.vsh".formatted(pc.shaderPaths.get(SPIRVUtils.ShaderKind.VERTEX_SHADER))));
        b.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER,
                ShaderLoadUtil.loadShader(path, "%s.fsh".formatted(pc.shaderPaths.get(SPIRVUtils.ShaderKind.FRAGMENT_SHADER))));
        GraphicsPipeline p = b.createGraphicsPipeline();
        for (var buf : p.getBuffers()) buf.setUseGlobalBuffer(true);
        return p;
    }
}
