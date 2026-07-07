package net.kaiten;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Computes per-pixel camera motion vectors for DLSS Super Resolution.
 *
 * <p>A single compute dispatch that reads the render-resolution depth buffer and writes an
 * RG16F motion-vector image (UV-space displacement, current→previous). This is the missing
 * temporal input that makes DLSS upscaling stable during camera movement — without it DLSS
 * reprojects history to the wrong place and the image shimmers/flickers.
 *
 * <p>Uses the un-jittered view-projection matrices captured in {@link DlssFrameState}. Runs
 * before {@code slDlssEvaluate} in the upscaling path; the resulting image is tagged as the
 * DLSS motion-vector resource (with {@code cameraMotionIncluded = true}).
 */
public final class DlssMotionVectors {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private DlssMotionVectors() {}

    private static long pipeline      = VK_NULL_HANDLE;
    private static long pipeLayout    = VK_NULL_HANDLE;
    private static long dsLayout       = VK_NULL_HANDLE;
    private static long descPool       = VK_NULL_HANDLE;
    private static long descSet        = VK_NULL_HANDLE;
    private static boolean initialized = false;
    private static boolean failed      = false;

    // Cached descriptor bindings to avoid redundant updates.
    private static long boundDepthView = 0;
    private static long boundMvView    = 0;

    private static final int WORKGROUP = 8;
    private static final int PUSH_BYTES = 128; // two mat4 (invCurrentVP, previousVP)

    /** Compile the pipeline once. Safe to call repeatedly. */
    public static void init() {
        if (initialized || failed) return;
        try {
            VkDevice device = Vulkan.getVkDevice();
            String src = readResource("/assets/vulkanmod/shaders/dlss/dlss_mv.comp");
            SPIRVUtils.SPIRV spv = SPIRVUtils.compileShader("dlss_mv.comp", src, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
            long module = createShaderModule(device, spv.bytecode());

            dsLayout = createDescriptorSetLayout(device);
            pipeLayout = createPipelineLayout(device, dsLayout);
            pipeline = createComputePipeline(device, module, pipeLayout);
            vkDestroyShaderModule(device, module, null);

            createDescriptorPoolAndSet(device);
            initialized = true;
            LOGGER.info("[DLSS-MV] Motion-vector compute pipeline compiled");
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("[DLSS-MV] init failed: {}", t.toString());
            destroy();
        }
    }

    /**
     * Records the MV compute dispatch into {@code cmd}. {@code depth} must be in
     * SHADER_READ_ONLY layout and {@code mvOut} in GENERAL layout on entry. Inserts a
     * barrier so the MV writes are visible to the subsequent DLSS evaluate (shader read).
     */
    public static boolean record(VkCommandBuffer cmd, VulkanImage depth, VulkanImage mvOut,
                                 int renderW, int renderH) {
        if (!initialized || failed) return false;
        try {
            VkDevice device = Vulkan.getVkDevice();
            updateDescriptorSet(device, depth, mvOut);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeLayout,
                        0, stack.longs(descSet), null);

                // Push constants: invCurrentVP (un-jittered), previousVP. Row/col layout must
                // match the compute shader's mat4 * vec4 (column-major, as JOML stores them).
                ByteBuffer pc = stack.malloc(PUSH_BYTES);
                pc.put(0, DlssFrameState.invCurrentVPBuf.buffer, 0, 64);
                pc.put(64, DlssFrameState.previousVPBuf.buffer, 0, 64);
                vkCmdPushConstants(cmd, pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            }
            vkCmdDispatch(cmd, (renderW + WORKGROUP - 1) / WORKGROUP,
                    (renderH + WORKGROUP - 1) / WORKGROUP, 1);

            // Make MV writes visible to the DLSS evaluate that reads them.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0, b, null, null);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[DLSS-MV] record failed: {}", t.toString());
            return false;
        }
    }

    // ---- Vulkan setup ----

    private static long createDescriptorSetLayout(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binds);
            long[] p = new long[1];
            check(vkCreateDescriptorSetLayout(device, info, null, p), "MV DS layout");
            return p[0];
        }
    }

    private static long createPipelineLayout(VkDevice device, long dsLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(dsLayout)).pPushConstantRanges(pcr);
            long[] p = new long[1];
            check(vkCreatePipelineLayout(device, info, null, p), "MV pipeline layout");
            return p[0];
        }
    }

    private static long createComputePipeline(VkDevice device, long module, long layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType$Default();
            info.get(0).stage(stage).layout(layout);
            long[] p = new long[1];
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, p), "MV compute pipeline");
            return p[0];
        }
    }

    private static long createShaderModule(VkDevice device, ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(spirv);
            long[] p = new long[1];
            check(vkCreateShaderModule(device, info, null, p), "MV shader module");
            return p[0];
        }
    }

    private static void createDescriptorPoolAndSet(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            VkDescriptorPoolCreateInfo dpInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().pPoolSizes(sizes).maxSets(1);
            long[] p = new long[1];
            check(vkCreateDescriptorPool(device, dpInfo, null, p), "MV descriptor pool");
            descPool = p[0];

            VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descPool).pSetLayouts(stack.longs(dsLayout));
            long[] sets = new long[1];
            check(vkAllocateDescriptorSets(device, alloc, sets), "MV descriptor set");
            descSet = sets[0];
        }
    }

    private static void updateDescriptorSet(VkDevice device, VulkanImage depth, VulkanImage mvOut) {
        long depthView = depth.getImageView();
        long mvView = mvOut.getImageView();
        if (boundDepthView == depthView && boundMvView == mvView) return;
        boundDepthView = depthView; boundMvView = mvView;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer depthInfo = VkDescriptorImageInfo.calloc(1, stack);
            depthInfo.get(0).sampler(depth.getSampler())
                    .imageView(depthView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkDescriptorImageInfo.Buffer mvInfo = VkDescriptorImageInfo.calloc(1, stack);
            mvInfo.get(0).imageView(mvView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().dstSet(descSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(depthInfo);
            writes.get(1).sType$Default().dstSet(descSet).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).pImageInfo(mvInfo);
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = DlssMotionVectors.class.getResourceAsStream(path)) {
            if (in == null) throw new RuntimeException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void check(int res, String what) {
        if (res != VK_SUCCESS) throw new RuntimeException(what + " failed: " + res);
    }

    public static void destroy() {
        VkDevice device = Vulkan.getVkDevice();
        if (pipeline != VK_NULL_HANDLE) { vkDestroyPipeline(device, pipeline, null); pipeline = VK_NULL_HANDLE; }
        if (pipeLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device, pipeLayout, null); pipeLayout = VK_NULL_HANDLE; }
        if (dsLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device, dsLayout, null); dsLayout = VK_NULL_HANDLE; }
        if (descPool != VK_NULL_HANDLE) { vkDestroyDescriptorPool(device, descPool, null); descPool = VK_NULL_HANDLE; }
        boundDepthView = boundMvView = 0;
        initialized = false;
    }
}
