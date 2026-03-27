package com.github.cfmsm.gcommander;

import org.lwjgl.system.*;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

import static org.lwjgl.vulkan.VK10.*;

public class GCommand implements AutoCloseable {
    private final VkDevice device;
    long pipeline;
    long pipelineLayout;
    long descriptorSetLayout;
    long descriptorSet;
    long descriptorPool;
    private long lastInputHash = -1;
    private long lastOutputHash = -1;
    private int lastInputCount = -1;
    private int lastOutputCount = -1;
    protected static final Map<Integer, PipelineBundle> pipelineCache = new ConcurrentHashMap<>();
    public GCommand(GCommander gCommand, String glslSource) {
        this.descriptorPool=gCommand.descriptorPool;
        GCommander.VulkanContext vulkanContext = gCommand.vulkanContext;
        this.device = vulkanContext.device;

        byte[] spirv = compileGLSLToSPIRV(glslSource);
        int shaderKey = Arrays.hashCode(spirv) * 31 + device.hashCode();

        PipelineBundle cached = pipelineCache.get(shaderKey);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (cached != null) {
                this.pipeline = cached.pipeline;
                this.pipelineLayout = cached.pipelineLayout;
                this.descriptorSetLayout = cached.descriptorSetLayout;
            } else {
                createDescriptorSetLayout(stack);
                createPipeline(spirv, stack);

                pipelineCache.put(shaderKey,
                        new PipelineBundle(pipeline, pipelineLayout, descriptorSetLayout));
            }

            allocateDescriptorSet(stack);
        }
    }

    private void createDescriptorSetLayout(MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
        bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(bindings);

        LongBuffer pLayout = stack.mallocLong(1);
        int err = vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
        if (err != VK_SUCCESS) {
            throw new RuntimeException("Failed to create descriptor set layout: " + err);
        }
        descriptorSetLayout = pLayout.get(0);
    }


    private void allocateDescriptorSet(MemoryStack stack) {
        VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default().descriptorPool(descriptorPool)
                .pSetLayouts(stack.longs(descriptorSetLayout));

        LongBuffer pDescriptorSet = stack.mallocLong(1);
        int err = vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);
        if (err != VK_SUCCESS) {
            throw new RuntimeException("Failed to allocate descriptor set: " + err);
        }
        descriptorSet = pDescriptorSet.get(0);
    }

    private void createPipeline(byte[] spirv, MemoryStack stack) {
        VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default().pCode(stack.bytes(spirv));

        LongBuffer pShaderModule = stack.mallocLong(1);
        int err = vkCreateShaderModule(device, shaderInfo, null, pShaderModule);
        if (err != VK_SUCCESS) {
            throw new RuntimeException("Failed to create shader module: " + err);
        }
        long shaderModule = pShaderModule.get(0);

        ByteBuffer mainName = stack.UTF8("main");
        VkPipelineShaderStageCreateInfo shaderStage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule)
                .pName(mainName);

        VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default().pSetLayouts(stack.longs(descriptorSetLayout));

        LongBuffer pPipelineLayout = stack.mallocLong(1);
        err = vkCreatePipelineLayout(device, layoutInfo, null, pPipelineLayout);
        if (err != VK_SUCCESS) {
            throw new RuntimeException("Failed to create pipeline layout: " + err);
        }
        pipelineLayout = pPipelineLayout.get(0);

        VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                .sType$Default().stage(shaderStage).layout(pipelineLayout);

        LongBuffer pPipeline = stack.mallocLong(1);
        err = vkCreateComputePipelines(device, 0, pipelineInfo, null, pPipeline);
        if (err != VK_SUCCESS) {
            throw new RuntimeException("Failed to create compute pipeline: " + err);
        }
        pipeline = pPipeline.get(0);

        vkDestroyShaderModule(device, shaderModule, null);
    }

    void updateDescriptorSet(VkDevice device, GBuffer[] inputBuffers, GBuffer[] outputBuffers, MemoryStack stack) {
        // Early exit if buffers haven't changed
        long inputHash = hashBufferArray(inputBuffers);
        long outputHash = hashBufferArray(outputBuffers);

        if (inputHash == lastInputHash && outputHash == lastOutputHash &&
                inputBuffers.length == lastInputCount && outputBuffers.length == lastOutputCount) {
            return; // No update needed
        }

        lastInputHash = inputHash;
        lastOutputHash = outputHash;
        lastInputCount = inputBuffers.length;
        lastOutputCount = outputBuffers.length;

        VkDescriptorBufferInfo.Buffer bufferInfos = VkDescriptorBufferInfo.calloc(inputBuffers.length + outputBuffers.length, stack);

        for (int i = 0; i < inputBuffers.length; i++) {
            bufferInfos.get(i).buffer(inputBuffers[i].handle).offset(0).range(VK_WHOLE_SIZE);
        }
        for (int i = 0; i < outputBuffers.length; i++) {
            bufferInfos.get(inputBuffers.length + i).buffer(outputBuffers[i].handle).offset(0).range(VK_WHOLE_SIZE);
        }

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
        writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0).dstArrayElement(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(inputBuffers.length)
                .pBufferInfo(bufferInfos.slice(0, inputBuffers.length));
        writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1).dstArrayElement(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(outputBuffers.length)
                .pBufferInfo(bufferInfos.slice(inputBuffers.length, outputBuffers.length));

        vkUpdateDescriptorSets(device, writes, null);
    }

    private long hashBufferArray(GBuffer[] buffers) {
        long hash = 0;
        for (GBuffer buf : buffers) {
            hash = hash * 31 + buf.handle;
        }
        return hash;
    }
    @Override
    public void close() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (descriptorSet != 0) vkFreeDescriptorSets(device, descriptorPool, stack.longs(descriptorSet));
        }
    }
    private static byte[] compileGLSLToSPIRV(String glslSource) {
        // Check cache first
        byte[] cached = GCommander.shaderCache.get(glslSource);
        if (cached != null) {
            return cached;
        }

        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();

        long result = Shaderc.shaderc_compile_into_spv(compiler, glslSource,
                Shaderc.shaderc_glsl_compute_shader, "c.glsl", "main", options);

        if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
            String errorMsg = Shaderc.shaderc_result_get_error_message(result);
            Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
            throw new RuntimeException("Shader compilation failed: " + errorMsg);
        }

        ByteBuffer spirvBuffer = Shaderc.shaderc_result_get_bytes(result);
        assert spirvBuffer != null;
        byte[] spirv = new byte[spirvBuffer.remaining()];
        spirvBuffer.get(spirv);

        Shaderc.shaderc_result_release(result);
        Shaderc.shaderc_compile_options_release(options);
        Shaderc.shaderc_compiler_release(compiler);
        GCommander.shaderCache.putIfAbsent(glslSource, spirv);
        return spirv;
    }
    protected static class PipelineBundle {
        long pipeline;
        long pipelineLayout;
        long descriptorSetLayout;
        private static final Map<Integer, PipelineBundle> pipelineCache = new ConcurrentHashMap<>();
        PipelineBundle(long pipeline, long pipelineLayout, long descriptorSetLayout) {
            this.pipeline = pipeline;
            this.pipelineLayout = pipelineLayout;
            this.descriptorSetLayout = descriptorSetLayout;
        }
    }
}