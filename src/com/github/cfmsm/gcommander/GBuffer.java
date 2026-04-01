package com.github.cfmsm.gcommander;

import org.lwjgl.*;
import org.lwjgl.system.*;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

import static com.github.cfmsm.gcommander.GCommander.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class GBuffer implements AutoCloseable {
    long handle;
    long allocation;
    final long size;
    private final VkDevice device;
    protected final GCommander gCommander;
    private long stagingHandle;
    private long stagingAllocation;
    private ByteBuffer mappedStagingBuffer;
    private final boolean useStaging;
    private FloatBuffer stagingFloatBuffer;
    private IntBuffer stagingIntBuffer;
    private LongBuffer stagingLongBuffer;
    private DoubleBuffer stagingDoubleBuffer;
    private ShortBuffer stagingShortBuffer;
    private boolean stagingDirty = true;
    private final Object stagingLock = new Object();
    public GBuffer(GCommander gCommander, long size, int usageFlags) {
        size = Math.max(size, 1);
        this.gCommander = gCommander;
        this.gCommander.initializationFuture.join();
        this.device = gCommander.vulkanContext.device;
        this.size = size;
        this.useStaging = gCommander.useStageMapping;
        long finalSize = size;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .size(finalSize)
                        .usage(usageFlags | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                int usage = VMA_MEMORY_USAGE_GPU_TO_CPU;
                if (GCommander.hasVRAM) usage = VMA_MEMORY_USAGE_GPU_ONLY;
                VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(usage);
                LongBuffer pBuffer = stack.mallocLong(1);
                PointerBuffer pAllocation = stack.mallocPointer(1);
                int err = vmaCreateBuffer(gCommander.vulkanContext.allocator, bufferInfo, allocInfo,
                        pBuffer, pAllocation, null);
                if (err != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create buffer with VMA: " + err);
                }
                handle = pBuffer.get(0);
                allocation = pAllocation.get(0);
                long tmpStagingHandle = 0;
                long tmpStagingAllocation = 0;
                ByteBuffer tmpMapped = null;
                FloatBuffer tmpFloatBuffer = null;
                IntBuffer tmpIntBuffer = null;
                LongBuffer tmpLongBuffer = null;
                DoubleBuffer tmpDoubleBuffer = null;
                ShortBuffer tmpShortBuffer = null;
                if (useStaging) {
                    VkBufferCreateInfo stagingBufferInfo = VkBufferCreateInfo.calloc(stack)
                            .sType$Default()
                            .size(finalSize)
                            .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                            .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                    VmaAllocationCreateInfo stagingAllocInfo = VmaAllocationCreateInfo.calloc(stack)
                            .usage(VMA_MEMORY_USAGE_CPU_ONLY)
                            .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);
                    LongBuffer pStagingBuffer = stack.mallocLong(1);
                    PointerBuffer pStagingAllocation = stack.mallocPointer(1);
                    VmaAllocationInfo allocationInfo = VmaAllocationInfo.calloc(stack);
                    err = vmaCreateBuffer(gCommander.vulkanContext.allocator, stagingBufferInfo, stagingAllocInfo,
                            pStagingBuffer, pStagingAllocation, allocationInfo);
                    if (err != VK_SUCCESS) {
                        throw new RuntimeException("Failed to create staging buffer with VMA: " + err);
                    }
                    tmpStagingHandle = pStagingBuffer.get(0);
                    tmpStagingAllocation = pStagingAllocation.get(0);
                    tmpMapped = memByteBuffer(allocationInfo.pMappedData(), (int) finalSize);
                    tmpFloatBuffer = tmpMapped.asFloatBuffer();
                    tmpIntBuffer = tmpMapped.asIntBuffer();
                    tmpLongBuffer = tmpMapped.asLongBuffer();
                    tmpDoubleBuffer = tmpMapped.asDoubleBuffer();
                    tmpShortBuffer = tmpMapped.asShortBuffer();
                }
                stagingHandle = tmpStagingHandle;
                stagingAllocation = tmpStagingAllocation;
                mappedStagingBuffer = tmpMapped;
                stagingFloatBuffer = tmpFloatBuffer;
                stagingIntBuffer = tmpIntBuffer;
                stagingLongBuffer = tmpLongBuffer;
                stagingDoubleBuffer = tmpDoubleBuffer;
                stagingShortBuffer = tmpShortBuffer;
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

    public static GBuffer ofShort(GCommander gCommander, long size) {
        return new GBuffer(gCommander, size * Short.BYTES);
    }

    public static GBuffer ofByte(GCommander gCommander, long size) {
        return new GBuffer(gCommander, size * Byte.BYTES);
    }

    // ==================== SYNCHRONOUS METHODS (backward compatible) ====================

    public void upload(float[] data) {
        markDirty();
        if (useStaging) uploadViaStagingBuffer(data);
        else uploadDirect(data);
    }

    public void upload(int[] data) {
        markDirty();
        if (useStaging) uploadViaStagingBuffer(data);
        else uploadDirect(data);
    }

    public void upload(long[] data) {
        markDirty();
        if (useStaging) uploadViaStagingBuffer(data);
        else uploadDirect(data);
    }

    public void upload(double[] data) {
        markDirty();
        if (useStaging) uploadViaStagingBuffer(data);
        else uploadDirect(data);
    }

    public void upload(short[] data) {
        markDirty();
        if (useStaging) uploadViaStagingBuffer(data);
        else uploadDirect(data);
    }

    public void upload(byte[] data) {
        markDirty();
        if (useStaging) uploadViaStagingBuffer(data);
        else uploadDirect(data);
    }

    // ==================== ASYNC UPLOAD METHODS ====================

    public CompletableFuture<Void> uploadAsync(float[] data) {
        return CompletableFuture.runAsync(() -> upload(data), virtualThreadExecutor);
    }

    public CompletableFuture<Void> uploadAsync(int[] data) {
        return CompletableFuture.runAsync(() -> upload(data), virtualThreadExecutor);
    }

    public CompletableFuture<Void> uploadAsync(long[] data) {
        return CompletableFuture.runAsync(() -> upload(data), virtualThreadExecutor);
    }

    public CompletableFuture<Void> uploadAsync(double[] data) {
        return CompletableFuture.runAsync(() -> upload(data), virtualThreadExecutor);
    }

    public CompletableFuture<Void> uploadAsync(short[] data) {
        return CompletableFuture.runAsync(() -> upload(data), virtualThreadExecutor);
    }

    public CompletableFuture<Void> uploadAsync(byte[] data) {
        return CompletableFuture.runAsync(() -> upload(data), virtualThreadExecutor);
    }

    // ==================== DIRECT UPLOAD METHODS ====================

    public void uploadDirect(float[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer mappedBuffer = memByteBuffer(pData.get(0), (int) size);
            FloatBuffer floatBuf = mappedBuffer.asFloatBuffer();
            floatBuf.put(data);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
        }
    }

    public void uploadDirect(int[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer mappedBuffer = memByteBuffer(pData.get(0), (int) size);
            IntBuffer intBuf = mappedBuffer.asIntBuffer();
            intBuf.put(data);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
        }
    }

    public void uploadDirect(long[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer mappedBuffer = memByteBuffer(pData.get(0), (int) size);
            LongBuffer longBuf = mappedBuffer.asLongBuffer();
            longBuf.put(data);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
        }
    }

    public void uploadDirect(double[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer mappedBuffer = memByteBuffer(pData.get(0), (int) size);
            DoubleBuffer doubleBuf = mappedBuffer.asDoubleBuffer();
            doubleBuf.put(data);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
        }
    }

    public void uploadDirect(short[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer mappedBuffer = memByteBuffer(pData.get(0), (int) size);
            ShortBuffer shortBuf = mappedBuffer.asShortBuffer();
            shortBuf.put(data);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
        }
    }

    public void uploadDirect(byte[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer mappedBuffer = memByteBuffer(pData.get(0), (int) size);
            mappedBuffer.put(data);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
        }
    }

    // ==================== STAGING BUFFER UPLOAD METHODS ====================

    public void uploadViaStagingBuffer(float[] data) {
        synchronized (stagingLock) {
            stagingFloatBuffer.clear();
            stagingFloatBuffer.put(data);
            stagingFloatBuffer.flip();
        }
    }

    public void uploadViaStagingBuffer(int[] data) {
        synchronized (stagingLock) {
            stagingIntBuffer.clear();
            stagingIntBuffer.put(data);
            stagingIntBuffer.flip();
        }
    }

    public void uploadViaStagingBuffer(long[] data) {
        synchronized (stagingLock) {
            stagingLongBuffer.clear();
            stagingLongBuffer.put(data);
            stagingLongBuffer.flip();
        }
    }

    public void uploadViaStagingBuffer(double[] data) {
        synchronized (stagingLock) {
            stagingDoubleBuffer.clear();
            stagingDoubleBuffer.put(data);
            stagingDoubleBuffer.flip();
        }
    }

    public void uploadViaStagingBuffer(short[] data) {
        synchronized (stagingLock) {
            stagingShortBuffer.clear();
            stagingShortBuffer.put(data);
            stagingShortBuffer.flip();
        }
    }

    public void uploadViaStagingBuffer(byte[] data) {
        synchronized (stagingLock) {
            mappedStagingBuffer.clear();
            mappedStagingBuffer.put(data);
            mappedStagingBuffer.flip();
        }
    }

    // ==================== SYNCHRONOUS READ METHODS ====================

    public FloatBuffer readFloat() {
        return useStaging ? readFloatViaStagingBuffer() : readFloatDirect();
    }

    public IntBuffer readInt() {
        return useStaging ? readIntViaStagingBuffer() : readIntDirect();
    }

    public LongBuffer readLong() {
        return useStaging ? readLongViaStagingBuffer() : readLongDirect();
    }

    public DoubleBuffer readDouble() {
        return useStaging ? readDoubleViaStagingBuffer() : readDoubleDirect();
    }

    public ShortBuffer readShort() {
        return useStaging ? readShortViaStagingBuffer() : readShortDirect();
    }

    public ByteBuffer readByte() {
        if (useStaging) return readByteViaStagingBuffer();
        else {
            waitForReadDirect();
            return readByteDirect();
        }
    }

    // ==================== ASYNC READ METHODS ====================

    public CompletableFuture<FloatBuffer> readFloatAsync() {
        return CompletableFuture.supplyAsync(this::readFloat, virtualThreadExecutor);
    }

    public CompletableFuture<IntBuffer> readIntAsync() {
        return CompletableFuture.supplyAsync(this::readInt, virtualThreadExecutor);
    }

    public CompletableFuture<LongBuffer> readLongAsync() {
        return CompletableFuture.supplyAsync(this::readLong, virtualThreadExecutor);
    }

    public CompletableFuture<DoubleBuffer> readDoubleAsync() {
        return CompletableFuture.supplyAsync(this::readDouble, virtualThreadExecutor);
    }

    public CompletableFuture<ShortBuffer> readShortAsync() {
        return CompletableFuture.supplyAsync(this::readShort, virtualThreadExecutor);
    }

    public CompletableFuture<ByteBuffer> readByteAsync() {
        return CompletableFuture.supplyAsync(this::readByte, virtualThreadExecutor);
    }

    // ==================== DIRECT READ METHODS ====================

    public FloatBuffer readFloatDirect() {
        waitForReadDirect();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer byteBuffer = memByteBuffer(pData.get(0), (int) size);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
            return byteBuffer.asFloatBuffer();
        }
    }

    public IntBuffer readIntDirect() {
        waitForReadDirect();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer byteBuffer = memByteBuffer(pData.get(0), (int) size);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
            return byteBuffer.asIntBuffer();
        }
    }

    public LongBuffer readLongDirect() {
        waitForReadDirect();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer byteBuffer = memByteBuffer(pData.get(0), (int) size);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
            return byteBuffer.asLongBuffer();
        }
    }

    public DoubleBuffer readDoubleDirect() {
        waitForReadDirect();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer byteBuffer = memByteBuffer(pData.get(0), (int) size);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
            return byteBuffer.asDoubleBuffer();
        }
    }

    public ShortBuffer readShortDirect() {
        waitForReadDirect();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer byteBuffer = memByteBuffer(pData.get(0), (int) size);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
            return byteBuffer.asShortBuffer();
        }
    }

    public ByteBuffer readByteDirect() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = vmaMapMemory(gCommander.vulkanContext.allocator, allocation, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("Failed to map memory with VMA");
            ByteBuffer byteBuffer = memByteBuffer(pData.get(0), (int) size);
            vmaUnmapMemory(gCommander.vulkanContext.allocator, allocation);
            return byteBuffer;
        }
    }

    // ==================== STAGING BUFFER READ METHODS ====================

    public FloatBuffer readFloatViaStagingBuffer() {
        ensureStagingUpToDate();
        synchronized (stagingLock) {
            stagingFloatBuffer.position(0);
            return stagingFloatBuffer;
        }
    }

    public IntBuffer readIntViaStagingBuffer() {
        ensureStagingUpToDate();
        synchronized (stagingLock) {
            stagingIntBuffer.clear();
            return stagingIntBuffer;
        }
    }

    public LongBuffer readLongViaStagingBuffer() {
        ensureStagingUpToDate();
        synchronized (stagingLock) {
            stagingLongBuffer.clear();
            return stagingLongBuffer;
        }
    }

    public DoubleBuffer readDoubleViaStagingBuffer() {
        ensureStagingUpToDate();
        synchronized (stagingLock) {
            stagingDoubleBuffer.clear();
            return stagingDoubleBuffer;
        }
    }

    public ShortBuffer readShortViaStagingBuffer() {
        ensureStagingUpToDate();
        synchronized (stagingLock) {
            stagingShortBuffer.clear();
            return stagingShortBuffer;
        }
    }

    public ByteBuffer readByteViaStagingBuffer() {
        ensureStagingUpToDate();
        synchronized (stagingLock) {
            mappedStagingBuffer.clear();
            return mappedStagingBuffer;
        }
    }

    // ==================== BUFFER COPY METHODS ====================

    public void copyBufferViaSession(long srcBuffer, long dstBuffer, long bufferSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer vkCmdBuf = new VkCommandBuffer(gCommander.computeSession.getCommandBuffer(), device);
            long fence = gCommander.computeSession.getFence();
            vkResetCommandBuffer(vkCmdBuf, VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            vkResetFences(device, stack.longs(fence));
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
            vkQueueSubmit(new VkQueue(gCommander.vulkanContext.computeQueue, device), submitInfo, fence);
        }
    }

    public CompletableFuture<Void> copyBufferViaSessionAsync(long srcBuffer, long dstBuffer, long bufferSize) {
        return CompletableFuture.runAsync(() -> copyBufferViaSession(srcBuffer, dstBuffer, bufferSize), virtualThreadExecutor);
    }

    public void copyShortForStaging(short[] data) {
        copyBufferViaSession(stagingHandle, handle, (long) data.length * Short.BYTES);
    }

    public CompletableFuture<Void> copyShortForStagingAsync(short[] data) {
        return copyBufferViaSessionAsync(stagingHandle, handle, (long) data.length * Short.BYTES);
    }

    public void copyLongForStaging(long[] data) {
        copyBufferViaSession(stagingHandle, handle, (long) data.length * Long.BYTES);
    }

    public CompletableFuture<Void> copyLongForStagingAsync(long[] data) {
        return copyBufferViaSessionAsync(stagingHandle, handle, (long) data.length * Long.BYTES);
    }

    public void copyIntForStaging(int[] data) {
        copyBufferViaSession(stagingHandle, handle, (long) data.length * Integer.BYTES);
    }

    public CompletableFuture<Void> copyIntForStagingAsync(int[] data) {
        return copyBufferViaSessionAsync(stagingHandle, handle, (long) data.length * Integer.BYTES);
    }

    public void copyFloatForStaging(float[] data) {
        copyBufferViaSession(stagingHandle, handle, (long) data.length * Float.BYTES);
    }

    public CompletableFuture<Void> copyFloatForStagingAsync(float[] data) {
        return copyBufferViaSessionAsync(stagingHandle, handle, (long) data.length * Float.BYTES);
    }

    public void copyDoubleForStaging(double[] data) {
        copyBufferViaSession(stagingHandle, handle, (long) data.length * Double.BYTES);
    }

    public CompletableFuture<Void> copyDoubleForStagingAsync(double[] data) {
        return copyBufferViaSessionAsync(stagingHandle, handle, (long) data.length * Double.BYTES);
    }

    public void copyForStaging(byte[] data) {
        copyBufferViaSession(stagingHandle, handle, (long) data.length);
    }

    public CompletableFuture<Void> copyForStagingAsync(byte[] data) {
        return copyBufferViaSessionAsync(stagingHandle, handle, (long) data.length);
    }

    public void prepareStagingRead() {
        copyBufferViaSession(handle, stagingHandle, size);
        stagingDirty = false;
    }

    public CompletableFuture<Void> prepareStagingReadAsync() {
        return CompletableFuture.runAsync(() -> {
            copyBufferViaSession(handle, stagingHandle, size);
            stagingDirty = false;
        }, virtualThreadExecutor);
    }

    public synchronized void markDirty() {
        stagingDirty = true;
    }

    public void ensureStagingUpToDate() {
        if (stagingDirty) {
            prepareStagingRead();
        }
    }

    @Override
    public void close() {
        if (stagingAllocation != 0) {
            vmaDestroyBuffer(gCommander.vulkanContext.allocator, stagingHandle, stagingAllocation);
        }
        if (allocation != 0) {
            vmaDestroyBuffer(gCommander.vulkanContext.allocator, handle, allocation);
        }
    }

    public void waitForReadDirect() {
        while (true) {
            ByteBuffer bb = readByteDirect();
            byte[] buf = new byte[bb.capacity()];
            bb.get(buf);
            if (!Arrays.equals(buf, new byte[bb.capacity()])) break;
        }
    }
}