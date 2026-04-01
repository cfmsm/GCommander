package com.github.cfmsm.gcommander;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.util.concurrent.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static com.github.cfmsm.gcommander.GCommander.*;
import static org.lwjgl.vulkan.VK10.*;
public abstract class GExecution {
    protected CompletableFuture<Long> fenceFuture;
    protected long fence;
    protected VkDevice device;
    protected int maxConcurrent;
    public boolean fenceReady = false;
    LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(2048);
    public GExecution init(VkDevice device, Supplier<Long> fenceSupplier) {
        this.device = device;
        this.fenceFuture = CompletableFuture.supplyAsync(fenceSupplier, virtualThreadExecutor);
        return this;
    }

    public boolean waitIfIncomplete() {
        if (!fenceReady) throw new FatalGCommanderException("Error: Fence is not ready. Please call prepareFence() before using waitIfIncomplete().");
        else {
            boolean isIncomplete = !isComplete();
            if (isIncomplete) waitForFence();
            return isIncomplete;
        }
    }

    public boolean isComplete() {
        if (!fenceReady) throw new FatalGCommanderException("Error: Fence is not ready. Please call prepareFence() before using isComplete().");
        else return vkGetFenceStatus(device, fence) == VK_SUCCESS;
    }

    public void waitForFence() {
        if (!fenceReady) throw new FatalGCommanderException("Error: Fence is not ready. Please call prepareFence() before using waitForFence().");
        else try (MemoryStack stack = MemoryStack.stackPush()) {
            vkWaitForFences(device, stack.longs(fence), true, Long.MAX_VALUE);
        }
    }
    public abstract void cleanup();

    public long getFence() {
        if (!fenceReady) throw new FatalGCommanderException("Error: Fence is not ready. Please call prepareFence() before using getFence().");
        else return fence;
    }

    public VkDevice getDevice() {
        return device;
    } public void prepareFence() {
        this.fence = fenceFuture.join();
        this.fenceReady =true;
    }
}