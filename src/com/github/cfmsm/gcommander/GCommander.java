package com.github.cfmsm.gcommander;

import static com.github.cfmsm.gcommander.GCommand.*;

import com.esotericsoftware.kryo.kryo5.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.*;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import oshi.*;
import com.esotericsoftware.kryo.kryo5.io.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK13.*;

public class GCommander {
  protected final VulkanContext vulkanContext;
  protected ComputeSession computeSession;
  public static boolean quitOnFatalError = true;
  public static final SystemInfo si = new SystemInfo();
  protected long descriptorPool;
  public static final boolean hasVRAM = si.getHardware().getGraphicsCards().getFirst().getVRam() != 0;
  public boolean useStageMapping = hasVRAM;
  public static final Map<String, byte[]> shaderCache = new ConcurrentHashMap<>();
  public static final int FRAME_LATENCY = 2;
  public static final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private volatile boolean initialized = false;
  protected CompletableFuture<Void> initializationFuture;
  public GCommander() {
    vulkanContext = new VulkanContext();
  }

  public void initialize(int sets) {
    initializationFuture = CompletableFuture.runAsync(()->{
    try (MemoryStack stack = MemoryStack.stackPush()) {
      vulkanContext.initialize();
      this.computeSession = new ComputeSession(vulkanContext);
      createDescriptorPool(stack, sets);
      this.initialized = true;
      }
    }, virtualThreadExecutor);
  }
  public CompletableFuture<Void> initializeAsync(int sets) {
    return CompletableFuture.runAsync(()->initialize(sets));
  }
  public void cleanup() {
    initialized = false;
    Thread.startVirtualThread(()-> {
              computeSession.cleanup();
              vulkanContext.cleanup();
              for (GCommand.PipelineBundle bundle : pipelineCacheMap.values()) {
                vkDestroyPipeline(vulkanContext.device, bundle.pipeline, null);
                vkDestroyPipelineLayout(vulkanContext.device, bundle.pipelineLayout, null);
                vkDestroyDescriptorSetLayout(vulkanContext.device, bundle.descriptorSetLayout, null);
              }
              pipelineCacheMap.clear();
              virtualThreadExecutor.shutdown();
            });
  }

  public GExecution execute(GCommand shader, GBuffer inputBuffer, GBuffer outputBuffer,
                            int groupCountX, int groupCountY, int groupCountZ) {
    GBuffer[] inputs = (inputBuffer == null)
            ? new GBuffer[]{new GBuffer(outputBuffer.gCommander, 1)}
            : new GBuffer[]{inputBuffer};
    GBuffer[] outputs = new GBuffer[]{outputBuffer};
    return execute(new GCommand[]{shader},
            new GBuffer[][]{inputs},
            new GBuffer[][]{outputs},
            new int[]{groupCountX},
            new int[]{groupCountY},
            new int[]{groupCountZ});
  }

  /**
   * Execute shaders asynchronously with deferred descriptor updates.
   */
  public CompletableFuture<GExecution> executeAsync(GCommand shader, GBuffer inputBuffer, GBuffer outputBuffer,
                                                    int groupCountX, int groupCountY, int groupCountZ) {
    return CompletableFuture.supplyAsync(() ->
            execute(shader, inputBuffer, outputBuffer, groupCountX, groupCountY, groupCountZ)
    );
  }

  private GExecution execute(GCommand[] shaders, GBuffer[][] inputBuffers, GBuffer[][] outputBuffers,
                             int[] groupCountX, int[] groupCountY, int[] groupCountZ) {
    if (inputBuffers == null) {
      inputBuffers = new GBuffer[][]{new GBuffer[]{new GBuffer(outputBuffers[0][0].gCommander, 1)}};
    }
    GBuffer[][] finalInputs = inputBuffers;
    return new GExecution() {
      @Override
      public void cleanup() {}
    }.init(vulkanContext.device, () -> {
      computeSession.execute(shaders, finalInputs, outputBuffers, groupCountX, groupCountY, groupCountZ);
      return computeSession.getFence();
    });
  }

    public boolean isInitialized() {
        return initialized;
    }

    protected static class VulkanContext implements AutoCloseable {
    private VkInstance instance;
    protected VkPhysicalDevice physicalDevice;
    protected VkDevice device;
    protected long computeQueue;
    protected long allocator;
    private int computeQueueFamily;
    private VkPhysicalDeviceProperties deviceProperties;
    private VkPhysicalDeviceMemoryProperties memoryProperties;

    void initialize() {
      try (MemoryStack stack = MemoryStack.stackPush()) {
        createInstance(stack);
        selectPhysicalDevice(stack);
        createLogicalDevice(stack);
        cacheDeviceProperties();
        createAllocator(stack);
      }
    }

      private void createInstance(MemoryStack stack) {
          VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                  .sType$Default()
                  .pApplicationName(stack.UTF8("GCommand GPGPU"))
                  .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                  .apiVersion(VK_MAKE_VERSION(1, 3, 0));
          VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                  .sType$Default()
                  .pApplicationInfo(appInfo);
          PointerBuffer pInstance = stack.mallocPointer(1);
          int err = vkCreateInstance(createInfo, null, pInstance);
          if (err != VK_SUCCESS) {
            throw new RuntimeException("Failed to create Vulkan instance: " + err);
          }
          instance = new VkInstance(pInstance.get(0), createInfo);
      }

    private void selectPhysicalDevice(MemoryStack stack) {
      IntBuffer deviceCount = stack.mallocInt(1);
      vkEnumeratePhysicalDevices(instance, deviceCount, null);
      if (deviceCount.get(0) == 0) {
        throw new RuntimeException("No GPU devices found!");
      }
      PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
      vkEnumeratePhysicalDevices(instance, deviceCount, devices);
      physicalDevice = new VkPhysicalDevice(devices.get(0), instance);
      findComputeQueueFamily(stack);
    }

    private void cacheDeviceProperties() {
      deviceProperties = VkPhysicalDeviceProperties.malloc();
      vkGetPhysicalDeviceProperties(physicalDevice, deviceProperties);
      memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
      vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);
    }

    private void findComputeQueueFamily(MemoryStack stack) {
      IntBuffer queueFamilyCount = stack.mallocInt(1);
      vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueFamilyCount, null);
      VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.malloc(queueFamilyCount.get(0));
      vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueFamilyCount, queueFamilies);
      computeQueueFamily = -1;
      for (int i = 0; i < queueFamilies.capacity(); i++) {
        if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
          computeQueueFamily = i;
          break;
        }
      }
      queueFamilies.free();
      if (computeQueueFamily == -1) {
        throw new RuntimeException("No compute queue family found!");
      }
    }

    private void createLogicalDevice(MemoryStack stack) {
      VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack);
      queueCreateInfos.get(0)
              .sType$Default()
              .queueFamilyIndex(computeQueueFamily)
              .pQueuePriorities(stack.floats(1.0f));
      VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack);
      deviceCreateInfo.sType$Default()
              .pQueueCreateInfos(queueCreateInfos)
              .queueCreateInfoCount();
      PointerBuffer pDevice = stack.mallocPointer(1);
      int err = vkCreateDevice(physicalDevice, deviceCreateInfo, null, pDevice);
      if (err != VK_SUCCESS) {
        throw new RuntimeException("Failed to create logical device: " + err);
      }
      device = new VkDevice(pDevice.get(0), physicalDevice, deviceCreateInfo);
      PointerBuffer pQueue = stack.mallocPointer(1);
      vkGetDeviceQueue(device, computeQueueFamily, 0, pQueue);
      computeQueue = pQueue.get(0);
    }

    private void createAllocator(MemoryStack stack) {
      VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack)
              .set(instance, device);
      VmaAllocatorCreateInfo allocatorInfo = VmaAllocatorCreateInfo.calloc(stack)
              .pVulkanFunctions(functions)
              .instance(instance)
              .physicalDevice(physicalDevice)
              .device(device)
              .vulkanApiVersion(VK_API_VERSION_1_3);
      PointerBuffer pAllocator = stack.mallocPointer(1);
      int err = vmaCreateAllocator(allocatorInfo, pAllocator);
      if (err != VK_SUCCESS) {
        throw new RuntimeException("Failed to create VMA allocator: " + err);
      }
      allocator = pAllocator.get(0);
    }

    public int getComputeQueueFamily() {
      return computeQueueFamily;
    }

    public VkPhysicalDeviceProperties getDeviceProperties() {
      return deviceProperties;
    }

    public VkPhysicalDeviceMemoryProperties getMemoryProperties() {
      return memoryProperties;
    }

    @Override
    public void close() {
      if (allocator != 0) {
        vmaDestroyAllocator(allocator);
      }
      if (device != null) {
        vkDeviceWaitIdle(device);
        vkDestroyDevice(device, null);
      }
      if (instance != null) {
        vkDestroyInstance(instance, null);
      }
      if (deviceProperties != null) deviceProperties.free();
      if (memoryProperties != null) memoryProperties.free();
    }

    void cleanup() {
      close();
    }
  }
  protected static class ComputeSession implements AutoCloseable {
    private final VulkanContext vulkanContext;
    protected long commandPool;
    private final long[] fences;
    private final long[] commandBuffers;
    private int frameIndex = 0;
    private final Object frameIndexLock = new Object();
    ComputeSession(VulkanContext vulkanContext) {
      this.vulkanContext = vulkanContext;
      this.fences = new long[FRAME_LATENCY];
      this.commandBuffers = new long[FRAME_LATENCY];
      try (MemoryStack stack = MemoryStack.stackPush()) {
        createFences(stack);
        createCommandPool(stack);
        allocateCommandBuffers(stack);
      }
    }

    private void createCommandPool(MemoryStack stack) {
      VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
              .sType$Default()
              .queueFamilyIndex(vulkanContext.getComputeQueueFamily())
              .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
      LongBuffer pCommandPool = stack.mallocLong(1);
      int err = vkCreateCommandPool(vulkanContext.device, poolInfo, null, pCommandPool);
      if (err != VK_SUCCESS) {
        throw new RuntimeException("Failed to create command pool: " + err);
      }
      commandPool = pCommandPool.get(0);
    }

    public void execute(GCommand[] shaders, GBuffer[][] inputs, GBuffer[][] outputs,
                        int[] gx, int[] gy, int[] gz) {
      if (inputs == null) inputs = new GBuffer[][]{new GBuffer[]{new GBuffer(outputs[0][0].gCommander, 1)}};
      GBuffer[][] finalInputs = inputs;
      executeInternal(shaders, finalInputs, outputs, gx, gy, gz);
    }
    private void executeInternal(GCommand[] shaders, GBuffer[][] inputs, GBuffer[][] outputs,
                                 int[] gx, int[] gy, int[] gz) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
        int currentFrameIndex;
        long fence;
        long cmd;
        synchronized (frameIndexLock) {
          currentFrameIndex = frameIndex;
          frameIndex = (frameIndex + 1) % FRAME_LATENCY;
          fence = fences[currentFrameIndex];
          cmd = commandBuffers[currentFrameIndex];
        }
        vkWaitForFences(vulkanContext.device, stack.longs(fence), true, Long.MAX_VALUE);
        vkResetFences(vulkanContext.device, stack.longs(fence));
        VkCommandBuffer vkCmd = new VkCommandBuffer(cmd, vulkanContext.device);
        vkResetCommandBuffer(vkCmd, VK_COMMAND_BUFFER_LEVEL_PRIMARY);
        VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        vkBeginCommandBuffer(vkCmd, begin);

        // Record all shader dispatches
        for (int i = 0; i < shaders.length; i++) {
          shaders[i].future.join();
          recordShaderDispatch(vkCmd, shaders[i], inputs[i], outputs[i], gx[i], gy[i], gz[i], stack);
        }
        vkEndCommandBuffer(vkCmd);
        VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType$Default()
                .pCommandBuffers(stack.pointers(cmd));
        vkQueueSubmit(new VkQueue(vulkanContext.computeQueue, vulkanContext.device), submit, fence);
      }
    }

    private void recordShaderDispatch(VkCommandBuffer vkCmd, GCommand shader,
                                      GBuffer[] inputs, GBuffer[] outputs,
                                      int gx, int gy, int gz, MemoryStack stack) {
      shader.updateDescriptorSet(vulkanContext.device, inputs, outputs, stack);
      vkCmdBindPipeline(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE, shader.pipeline);
      vkCmdBindDescriptorSets(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE, shader.pipelineLayout,
              0, stack.longs(shader.descriptorSet), null);
      vkCmdDispatch(vkCmd, gx, gy, gz);
      VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
              .sType$Default()
              .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
              .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
      vkCmdPipelineBarrier(vkCmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
              VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
    }
    private void allocateCommandBuffers(MemoryStack stack) {
      VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
              .sType$Default()
              .commandPool(commandPool)
              .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
              .commandBufferCount(FRAME_LATENCY);
      PointerBuffer pCommandBuffers = stack.mallocPointer(FRAME_LATENCY);
      int err = vkAllocateCommandBuffers(vulkanContext.device, allocInfo, pCommandBuffers);
      if (err != VK_SUCCESS) {
        throw new RuntimeException("Failed to allocate command buffers: " + err);
      }
      for (int i = 0; i < FRAME_LATENCY; i++) {
        commandBuffers[i] = pCommandBuffers.get(i);
      }
    }

    private void createFences(MemoryStack stack) {
      for (int i = 0; i < FRAME_LATENCY; i++) {
        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType$Default()
                .flags(VK_FENCE_CREATE_SIGNALED_BIT);
        LongBuffer pFence = stack.mallocLong(1);
        int err = vkCreateFence(vulkanContext.device, fenceInfo, null, pFence);
        if (err != VK_SUCCESS) {
          throw new RuntimeException("Failed to create fence: " + err);
        }
        fences[i] = pFence.get(0);
      }
    }

    public long getFence() {
      synchronized (frameIndexLock) {
        return fences[frameIndex];
      }
    }

    public long getCommandBuffer() {
      synchronized (frameIndexLock) {
        return commandBuffers[frameIndex];
      }
    }

    @Override
    public void close() {
      if (vulkanContext.device != null) {
        vkDeviceWaitIdle(vulkanContext.device);
        for (long fence : fences) {
          if (fence != 0) vkDestroyFence(vulkanContext.device, fence, null);
        }
        if (commandPool != 0) vkDestroyCommandPool(vulkanContext.device, commandPool, null);
      }
    }

    void cleanup() {
      close();
    }
  }

  private void createDescriptorPool(MemoryStack stack, int sets) {
    VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
    poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
    VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
            .sType$Default().pPoolSizes(poolSizes).maxSets(sets);
    LongBuffer pPool = stack.mallocLong(1);
    int err = vkCreateDescriptorPool(vulkanContext.device, poolInfo, null, pPool);
    if (err != VK_SUCCESS) {
      throw new RuntimeException("Failed to create descriptor pool: " + err);
    }
    descriptorPool = pPool.get(0);
  }
}