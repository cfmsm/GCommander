package com.github.cfmsm.gcommander;

import org.lwjgl.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import java.nio.*;

import static org.lwjgl.vulkan.VK10.*;

public class GBuffer implements AutoCloseable {
    final long handle;
    final long memory;
    final long size;
    private final VkDevice device;
    private final GCommander gCommander;
    private final int usageFlags;

    // Staging buffer for uploads/reads
    private final long stagingHandle;
    private final long stagingMemory;
    private final ByteBuffer mappedStagingBuffer;
    private final boolean useStaging;


    public GBuffer(GCommander gCommander, long size, int usageFlags) {
        size=Math.max(size, 1);
        this.gCommander = gCommander;
        this.device = gCommander.vulkanContext.device;
        this.size = size;
        this.usageFlags = usageFlags;
        this.useStaging = gCommander.useStageMapping;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memProps = gCommander.vulkanContext.getMemoryProperties();

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usageFlags | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int err = vkCreateBuffer(device, bufferInfo, null, pBuffer);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to create buffer");
            handle = pBuffer.get(0);

            VkMemoryRequirements memRequirements = VkMemoryRequirements.malloc();
            vkGetBufferMemoryRequirements(device, handle, memRequirements);

            int memoryTypeIndex = findMemoryType(memProps, memRequirements.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(memRequirements.size())
                    .memoryTypeIndex(memoryTypeIndex);

            LongBuffer pMemory = stack.mallocLong(1);
            err = vkAllocateMemory(device, allocInfo, null, pMemory);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to allocate buffer memory");
            memory = pMemory.get(0);

            vkBindBufferMemory(device, handle, memory, 0);
            memRequirements.free();

            // --- Staging buffer (persistent mapped) ---
            long tmpHandle = 0;
            long tmpMemory = 0;
            ByteBuffer tmpMapped = null;

            if (useStaging) {
                VkBufferCreateInfo stagingBufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .size(size)
                        .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

                LongBuffer pStagingBuffer = stack.mallocLong(1);
                err = vkCreateBuffer(device, stagingBufferInfo, null, pStagingBuffer);
                if (err != VK_SUCCESS) throw new RuntimeException("Failed to create staging buffer");
                tmpHandle = pStagingBuffer.get(0);

                VkMemoryRequirements stagingMemRequirements = VkMemoryRequirements.malloc();
                vkGetBufferMemoryRequirements(device, tmpHandle, stagingMemRequirements);

                int stagingMemoryTypeIndex = findMemoryType(memProps, stagingMemRequirements.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

                VkMemoryAllocateInfo stagingAllocInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .allocationSize(stagingMemRequirements.size())
                        .memoryTypeIndex(stagingMemoryTypeIndex);

                LongBuffer pStagingMemory = stack.mallocLong(1);
                err = vkAllocateMemory(device, stagingAllocInfo, null, pStagingMemory);
                if (err != VK_SUCCESS) throw new RuntimeException("Failed to allocate staging buffer memory");
                tmpMemory = pStagingMemory.get(0);

                vkBindBufferMemory(device, tmpHandle, tmpMemory, 0);

                PointerBuffer pData = stack.mallocPointer(1);
                err = vkMapMemory(device, tmpMemory, 0, VK_WHOLE_SIZE, 0, pData);
                if (err != VK_SUCCESS) throw new RuntimeException("Failed to map staging memory");
                tmpMapped = MemoryUtil.memByteBuffer(pData.get(0), (int) size);

                stagingMemRequirements.free();
            }

            stagingHandle = tmpHandle;
            stagingMemory = tmpMemory;
            mappedStagingBuffer = tmpMapped;
        }
    }

    public GBuffer(GCommander gCommander, long size) {
        this(gCommander, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    }
    public static GBuffer ofFloat(GCommander gCommander, long size) {
        return new GBuffer(gCommander, size * Float.BYTES);
    }
    public static GBuffer ofInt(GCommander gCommander, long size) {
        return new GBuffer(gCommander, size * Integer.BYTES);
    }
    public static GBuffer ofLong(GCommander gCommander, long size) {
        return new GBuffer(gCommander, size * Long.BYTES);
    }
    public static GBuffer ofDouble(GCommander gCommander, long size) {
        return new GBuffer(gCommander, size * Double.BYTES);
    }
    public void upload(float[] data) {
        if (useStaging) uploadViaStagingBuffer(data);
        else uploadDirect(data);
    }

    private void uploadDirect(float[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory");

            MemoryUtil.memByteBuffer(pData.get(0), (int) size).asFloatBuffer().put(data);

            vkUnmapMemory(device, memory);
        }
    }

    private void uploadViaStagingBuffer(float[] data) {
        mappedStagingBuffer.asFloatBuffer().clear().put(data);
        copyBufferViaSession(stagingHandle, handle, (long) data.length * Float.BYTES);
    }

    public float[] read() {
        if (useStaging) return readViaStagingBuffer();
        else return readDirect();
    }

    private float[] readDirect() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory");

            ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) size);
            float[] results = new float[(int) (size / Float.BYTES)];
            byteBuffer.asFloatBuffer().get(results);

            vkUnmapMemory(device, memory);
            return results;
        }
    }

    private float[] readViaStagingBuffer() {
        copyBufferViaSession(handle, stagingHandle, size);

        float[] results = new float[(int) (size / Float.BYTES)];
        mappedStagingBuffer.asFloatBuffer().clear().get(results);
        return results;
    }

    private void copyBufferViaSession(long srcBuffer, long dstBuffer, long bufferSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer vkCmdBuf = new VkCommandBuffer(gCommander.computeSession.getCommandBuffer(), device);

            vkResetCommandBuffer(vkCmdBuf, VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            vkResetFences(device, stack.longs(gCommander.computeSession.getFence()));
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(vkCmdBuf, beginInfo);

            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(0).dstOffset(0).size(bufferSize);
            vkCmdCopyBuffer(vkCmdBuf, srcBuffer, dstBuffer, region);

            vkEndCommandBuffer(vkCmdBuf);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(gCommander.computeSession.getCommandBuffer()));

            vkQueueSubmit(new VkQueue(gCommander.vulkanContext.computeQueue, device), submitInfo,
                    gCommander.computeSession.getFence());
        }
    }

    @Override
    public void close() {
        if (stagingMemory != 0) vkUnmapMemory(device, stagingMemory);
        if (stagingMemory != 0) vkFreeMemory(device, stagingMemory, null);
        if (stagingHandle != 0) vkDestroyBuffer(device, stagingHandle, null);
        if (memory != 0) vkFreeMemory(device, memory, null);
        if (handle != 0) vkDestroyBuffer(device, handle, null);
    }

    private static int findMemoryType(VkPhysicalDeviceMemoryProperties memProps, int typeFilter, int properties) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0 &&
                    (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        throw new RuntimeException("Failed to find suitable memory type!");
    } public void clear() {
        fill(0);
    } public void fill(int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer vkCmdBuf = new VkCommandBuffer(
                    gCommander.computeSession.getCommandBuffer(), device);

            vkResetCommandBuffer(vkCmdBuf, VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            vkResetFences(device, stack.longs(gCommander.computeSession.getFence()));

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(vkCmdBuf, beginInfo);

            // 🔥 GPU-side fill
            vkCmdFillBuffer(
                    vkCmdBuf,
                    handle,
                    0,
                    size,
                    value
            );

            vkEndCommandBuffer(vkCmdBuf);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(gCommander.computeSession.getCommandBuffer()));

            vkQueueSubmit(
                    new VkQueue(gCommander.vulkanContext.computeQueue, device),
                    submitInfo,
                    gCommander.computeSession.getFence()
            );
        }
    } public void sync() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkWaitForFences(device, stack.longs(gCommander.computeSession.getFence()), true, Long.MAX_VALUE);
        }
    }
}