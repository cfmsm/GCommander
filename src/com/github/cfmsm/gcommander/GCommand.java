package com.github.cfmsm.gcommander;

import org.lwjgl.system.*;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import org.lwjgl.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import static com.github.cfmsm.gcommander.GCommander.*;
import static org.lwjgl.vulkan.VK10.*;

public class GCommand implements AutoCloseable {
    private final VkDevice device;
    long pipeline;
    long pipelineLayout;
    long descriptorSetLayout;
    long descriptorSet;
    long descriptorPool;
    long pipelineCache;
    private long lastInputHash = -1;
    private long lastOutputHash = -1;
    private int lastInputCount = -1;
    private int lastOutputCount = -1;
    private static final Map<String, CompletableFuture<byte[]>> asyncShaderCache = new ConcurrentHashMap<>();
    protected static final Map<Integer, PipelineBundle> pipelineCacheMap = new ConcurrentHashMap<>();
    private static final Path PIPELINE_CACHE_DIR = Paths.get(System.getProperty("user.home"), ".cache", "gcommander");
    private static final String PIPELINE_CACHE_FILE = "vulkan_pipeline.cache";
    private final Object descriptorUpdateLock = new Object();
    protected CompletableFuture<Void> future;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    public GCommand(GCommander gCommand, String glslSource) {
        CompletableFuture<byte[]> spirvFuture = compileGLSLToSPIRVAsync(glslSource);
        gCommand.initializationFuture.join();
        this.descriptorPool = gCommand.descriptorPool;
        GCommander.VulkanContext vulkanContext = gCommand.vulkanContext;
        this.device = vulkanContext.device;
        future = CompletableFuture.runAsync(()-> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                byte[] spirv = spirvFuture.join();
                int shaderKey = Arrays.hashCode(spirv);
                PipelineBundle cached = pipelineCacheMap.get(shaderKey);
                if (cached != null) {
                    this.pipeline = cached.pipeline;
                    this.pipelineLayout = cached.pipelineLayout;
                    this.descriptorSetLayout = cached.descriptorSetLayout;
                    this.pipelineCache = cached.pipelineCache;
                } else {
                    createDescriptorSetLayout(stack);
                    initializePipelineCache(stack);
                    createPipeline(spirv, stack);
                    pipelineCacheMap.put(shaderKey, new PipelineBundle(pipeline, pipelineLayout, descriptorSetLayout, this.pipelineCache));
                }
                allocateDescriptorSet(stack);
            }
        }, virtualThreadExecutor);
    }
    public GCommand(GCommander gCommand, byte[] spirv) {
        gCommand.initializationFuture.join();
        this.descriptorPool = gCommand.descriptorPool;
        GCommander.VulkanContext vulkanContext = gCommand.vulkanContext;
        this.device = vulkanContext.device;
        future = CompletableFuture.runAsync(()-> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int shaderKey = Arrays.hashCode(spirv);
                PipelineBundle cached = pipelineCacheMap.get(shaderKey);
                if (cached != null) {
                    this.pipeline = cached.pipeline;
                    this.pipelineLayout = cached.pipelineLayout;
                    this.descriptorSetLayout = cached.descriptorSetLayout;
                    this.pipelineCache = cached.pipelineCache;
                } else {
                    createDescriptorSetLayout(stack);
                    initializePipelineCache(stack);
                    createPipeline(spirv, stack);
                    pipelineCacheMap.put(shaderKey, new PipelineBundle(pipeline, pipelineLayout, descriptorSetLayout, this.pipelineCache));
                }
                allocateDescriptorSet(stack);
            }
        }, virtualThreadExecutor);
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

    private void initializePipelineCache(MemoryStack stack) {
        byte[] cacheData = null;

        // Try to load cache from disk
        try {
            cacheData = loadPipelineCacheFromDisk();
        } catch (IOException e) {
            System.err.println("Failed to load pipeline cache from disk: " + e.getMessage());
            // Continue with empty cache
        }

        VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack)
                .sType$Default();

        if (cacheData != null && cacheData.length > 0) {
            cacheInfo.pInitialData(stack.bytes(cacheData));
        }

        LongBuffer pCache = stack.mallocLong(1);
        int err = vkCreatePipelineCache(device, cacheInfo, null, pCache);
        if (err != VK_SUCCESS) {
            throw new RuntimeException("Failed to create pipeline cache: " + err);
        }
        pipelineCache = pCache.get(0);
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
        err = vkCreateComputePipelines(device, pipelineCache, pipelineInfo, null, pPipeline);
        if (err != VK_SUCCESS) {
            throw new RuntimeException("Failed to create compute pipeline: " + err);
        }
        pipeline = pPipeline.get(0);

        // Save cache to disk after successful pipeline creation
        savePipelineCacheToDisk();

        vkDestroyShaderModule(device, shaderModule, null);
    }

    private void savePipelineCacheToDisk() {
        writer.submit(()-> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Create cache directory if it doesn't exist
                Files.createDirectories(PIPELINE_CACHE_DIR);

                // Get cache data size
                PointerBuffer pDataSize = stack.mallocPointer(1);
                int err = vkGetPipelineCacheData(device, pipelineCache, pDataSize, null);
                if (err != VK_SUCCESS) {
                    System.err.println("Failed to get pipeline cache data size: " + err);
                    return;
                }

                long dataSize = pDataSize.get(0);
                if (dataSize == 0) {
                    return; // Nothing to save
                }

                ByteBuffer cacheData = stack.malloc((int) dataSize);
                err = vkGetPipelineCacheData(device, pipelineCache, pDataSize, cacheData);
                if (err != VK_SUCCESS) {
                    System.err.println("Failed to get pipeline cache data: " + err);
                    return;
                }

                // Write to disk
                byte[] data = new byte[(int) dataSize];
                cacheData.get(data);
                Path cachePath = PIPELINE_CACHE_DIR.resolve(PIPELINE_CACHE_FILE);
                Files.write(cachePath, data, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            } catch (IOException e) {
                System.err.println("Failed to save pipeline cache to disk: " + e.getMessage());
            }
        });
    }

    private byte[] loadPipelineCacheFromDisk() throws IOException {
        Path cachePath = PIPELINE_CACHE_DIR.resolve(PIPELINE_CACHE_FILE);
        if (Files.exists(cachePath)) {
            return Files.readAllBytes(cachePath);
        }
        return null;
    }

    void updateDescriptorSet(VkDevice device, GBuffer[] inputBuffers, GBuffer[] outputBuffers, MemoryStack stack) {
        long inputHash = hashBufferArray(inputBuffers);
        long outputHash = hashBufferArray(outputBuffers);
        if (inputHash == lastInputHash && outputHash == lastOutputHash &&
                inputBuffers.length == lastInputCount && outputBuffers.length == lastOutputCount) {
            return;
        }

        synchronized (descriptorUpdateLock) {
            // Double-check after acquiring lock
            if (inputHash == lastInputHash && outputHash == lastOutputHash &&
                    inputBuffers.length == lastInputCount && outputBuffers.length == lastOutputCount) {
                return;
            }

            lastInputHash = inputHash;
            lastOutputHash = outputHash;
            lastInputCount = inputBuffers.length;
            lastOutputCount = outputBuffers.length;

            int inputCount = inputBuffers.length;
            int outputCount = outputBuffers.length;
            int bufferCount = inputCount + outputCount;
            VkDescriptorBufferInfo.Buffer bufferInfos = VkDescriptorBufferInfo.calloc(bufferCount, stack);
            int idx = 0;
            for (GBuffer buf : inputBuffers) {
                bufferInfos.get(idx++).buffer(buf.handle).offset(0).range(VK_WHOLE_SIZE);
            }
            for (GBuffer buf : outputBuffers) {
                bufferInfos.get(idx++).buffer(buf.handle).offset(0).range(VK_WHOLE_SIZE);
            }
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0).dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(inputCount)
                    .pBufferInfo(bufferInfos.slice(0, inputCount));
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1).dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(outputCount)
                    .pBufferInfo(bufferInfos.slice(inputCount, outputCount));
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    public CompletableFuture<Void> updateDescriptorSetAsync(VkDevice device, GBuffer[] inputBuffers, GBuffer[] outputBuffers) {
        return CompletableFuture.runAsync(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                updateDescriptorSet(device, inputBuffers, outputBuffers, stack);
            }
        }, virtualThreadExecutor);
    }

    private long hashBufferArray(GBuffer[] buffers) {
        long hash = 5381;
        for (GBuffer buf : buffers) {
            hash = ((hash << 5) + hash) ^ buf.handle;
        }
        return hash;
    }

    @Override
    public void close() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (descriptorSet != 0) vkFreeDescriptorSets(device, descriptorPool, stack.longs(descriptorSet));
            if (pipelineCache != 0) vkDestroyPipelineCache(device, pipelineCache, null);
        }
        writer.shutdown();
    }

    private static byte[] compileGLSLToSPIRV(String glslSource) {
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

    public static CompletableFuture<byte[]> compileGLSLToSPIRVAsync(String glslSource) {
        byte[] cached = GCommander.shaderCache.get(glslSource);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return asyncShaderCache.computeIfAbsent(glslSource,
                key -> CompletableFuture.supplyAsync(() -> compileGLSLToSPIRV(key), virtualThreadExecutor));
    }

    protected static class PipelineBundle {
        long pipeline;
        long pipelineLayout;
        long descriptorSetLayout;
        long pipelineCache;

        PipelineBundle(long pipeline, long pipelineLayout, long descriptorSetLayout) {
            this.pipeline = pipeline;
            this.pipelineLayout = pipelineLayout;
            this.descriptorSetLayout = descriptorSetLayout;
            this.pipelineCache = 0;
        }

        PipelineBundle(long pipeline, long pipelineLayout, long descriptorSetLayout, long pipelineCache) {
            this.pipeline = pipeline;
            this.pipelineLayout = pipelineLayout;
            this.descriptorSetLayout = descriptorSetLayout;
            this.pipelineCache = pipelineCache;
        }
    }
}