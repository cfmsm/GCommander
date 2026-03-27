package com.github.cfmsm.gcommander;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import oshi.*;
import oshi.hardware.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.cfmsm.gcommander.GCommand.pipelineCache;
import static org.lwjgl.vulkan.VK13.*;

public class GCommander {
  protected final VulkanContext vulkanContext;
  protected ComputeSession computeSession;
  public static final SystemInfo si = new SystemInfo();
  protected long descriptorPool;
  public static final HardwareAbstractionLayer hal = si.getHardware();
  public static final List<GraphicsCard> cards = hal.getGraphicsCards();
  public static final String vendor = cards.getFirst().getVendor().toLowerCase();
  public final boolean hasVRAM = new SystemInfo().getHardware().getGraphicsCards().getFirst().getVRam() != 0;
  public final boolean useStageMapping = hasVRAM || vendor.contains("apple");
  public static final Map<String, byte[]> shaderCache = new ConcurrentHashMap<>();
  public static final int FRAME_LATENCY = 2;
  public int gCommanders;
  public GCommander() {
    this.vulkanContext = new VulkanContext();
  }
  public void initialize(int sets, int gCommanders) {
    if (System.getProperty("os.name").toLowerCase().contains("mac")) gCommanders=0; //MoltenVK is NOT thread safe
    try (MemoryStack stack = MemoryStack.stackPush()) {
      this.gCommanders=gCommanders;
      vulkanContext.initialize();
      this.computeSession = new ComputeSession(vulkanContext);
      createDescriptorPool(stack, sets);
      System.out.println("[GCommand] ✓ Vulkan initialized");
    }
  }
  public void initialize(int sets) {
    initialize(sets, 0);
  }
  public void cleanup() {
    computeSession.cleanup();
    vulkanContext.cleanup();

    for (GCommand.PipelineBundle bundle : pipelineCache.values()) {
      vkDestroyPipeline(vulkanContext.device, bundle.pipeline, null);
      vkDestroyPipelineLayout(vulkanContext.device, bundle.pipelineLayout, null);
      vkDestroyDescriptorSetLayout(vulkanContext.device, bundle.descriptorSetLayout, null);
    }
    pipelineCache.clear();
  }

  protected static class VulkanContext implements AutoCloseable {
    private VkInstance instance;
    protected VkPhysicalDevice physicalDevice;
    protected VkDevice device;
    protected long computeQueue;
    private int computeQueueFamily;
    private VkPhysicalDeviceProperties deviceProperties;
    private VkPhysicalDeviceMemoryProperties memoryProperties;

    void initialize() {
      try (MemoryStack stack = MemoryStack.stackPush()) {
        createInstance(stack);
        selectPhysicalDevice(stack);
        cacheDeviceProperties();
        createLogicalDevice(stack);
      }
    }

    private void createInstance(MemoryStack stack) {
      VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
              .sType$Default()
              .pApplicationName(stack.UTF8("Vulkan GPGPU"))
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

  public void execute(GCommand shader, GBuffer[] inputBuffers, GBuffer[] outputBuffers,
                      int groupCountX, int groupCountY, int groupCountZ) {
    computeSession.execute(shader, inputBuffers, outputBuffers, groupCountX, groupCountY, groupCountZ);
  }

  public void execute(GCommand shader, GBuffer inputBuffer, GBuffer outputBuffer,
                      int groupCountX, int groupCountY, int groupCountZ) {
    computeSession.execute(shader, new GBuffer[]{inputBuffer}, new GBuffer[]{outputBuffer}, groupCountX, groupCountY, groupCountZ);
  }
  public void execute(GCommand[] shaders,
                      GBuffer[][] inputBuffers,
                      GBuffer[][] outputBuffers,
                      int[] groupCountX,
                      int[] groupCountY,
                      int[] groupCountZ) {
      computeSession.execute(shaders, inputBuffers, outputBuffers, groupCountX, groupCountY, groupCountZ);
  }
  public void execute(GCommand[] shaders, GBuffer[] inputBuffers, GBuffer[] outputBuffers, int[] groupCountX, int[] groupCountY, int[] groupCountZ) {
    execute(shaders, new GBuffer[][]{inputBuffers}, new GBuffer[][]{outputBuffers}, groupCountX, groupCountY, groupCountZ);
  }
  public void execute(GCommand[] shaders, GBuffer inputBuffer, GBuffer outputBuffer, int[] groupCountX, int[] groupCountY, int[] groupCountZ) {
    execute(shaders, new GBuffer[]{inputBuffer}, new GBuffer[]{outputBuffer}, groupCountX, groupCountY, groupCountZ);
  }
  protected class ComputeSession implements AutoCloseable {
    private final VulkanContext vulkanContext;
    protected long commandPool;
    private final long[] fences;
    private final long[] commandBuffers;
    private int frameIndex = 0;
    private final AtomicInteger numThreads = new AtomicInteger(0);
    ComputeSession(VulkanContext vulkanContext) {
      this.vulkanContext = vulkanContext;
      this.fences = new long[FRAME_LATENCY];
      this.commandBuffers = new long[FRAME_LATENCY];

      try (MemoryStack stack = MemoryStack.stackPush()) {
        createCommandPool(stack);
        allocateCommandBuffers(stack);
        createFences(stack);
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
    public void execute(GCommand[] shaders,
                        GBuffer[][] inputs,
                        GBuffer[][] outputs,
                        int[] gx, int[] gy, int[] gz) {
      Runnable r = ()-> {
        try (MemoryStack stack = MemoryStack.stackPush()) {
          long fence = fences[frameIndex];
          long cmd = commandBuffers[frameIndex];
          vkResetFences(vulkanContext.device, stack.longs(fence));

          VkCommandBuffer vkCmd = new VkCommandBuffer(cmd, vulkanContext.device);
          vkResetCommandBuffer(vkCmd, VK_COMMAND_BUFFER_LEVEL_PRIMARY);

          VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                  .sType$Default()
                  .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

          vkBeginCommandBuffer(vkCmd, begin);

          for (int i = 0; i < shaders.length; i++) {
            GCommand shader = shaders[i];

            shader.updateDescriptorSet(
                    vulkanContext.device,
                    inputs[i],
                    outputs[i],
                    stack
            );

            vkCmdBindPipeline(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE, shader.pipeline);

            vkCmdBindDescriptorSets(
                    vkCmd,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    shader.pipelineLayout,
                    0,
                    stack.longs(shader.descriptorSet),
                    null
            );

            vkCmdDispatch(vkCmd, gx[i], gy[i], gz[i]);
          }

          vkEndCommandBuffer(vkCmd);

          VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                  .sType$Default()
                  .pCommandBuffers(stack.pointers(cmd));
          vkQueueSubmit(new VkQueue(vulkanContext.computeQueue, vulkanContext.device), submit, fence);

          frameIndex = (frameIndex + 1) % FRAME_LATENCY;
        }
      };
      if (numThreads.get() < gCommanders) Thread.startVirtualThread(() -> {
        numThreads.incrementAndGet();
        try { r.run(); } finally { numThreads.decrementAndGet(); }
      });
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
        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
        LongBuffer pFence = stack.mallocLong(1);
        int err = vkCreateFence(vulkanContext.device, fenceInfo, null, pFence);
        if (err != VK_SUCCESS) {
          throw new RuntimeException("Failed to create fence: " + err);
        }
        fences[i] = pFence.get(0);
      }
    }
    public void execute(GCommand shader, GBuffer[] inputBuffers, GBuffer[] outputBuffers,
                        int groupCountX, int groupCountY, int groupCountZ) {
      Runnable r = ()-> {
        try (MemoryStack stack = MemoryStack.stackPush()) {
          long currentFence = fences[frameIndex];
          long currentCmdBuf = commandBuffers[frameIndex];
          vkResetFences(vulkanContext.device, stack.longs(currentFence));

          shader.updateDescriptorSet(vulkanContext.device, inputBuffers, outputBuffers, stack);
          executeComputeShader(shader, currentCmdBuf, groupCountX, groupCountY, groupCountZ, currentFence, stack);
          frameIndex = (frameIndex + 1) % FRAME_LATENCY;
        }
      };
      if (numThreads.get() < gCommanders) Thread.startVirtualThread(() -> {
        numThreads.incrementAndGet();
        try { r.run(); } finally { numThreads.decrementAndGet(); }
      });
    }

    private void executeComputeShader(GCommand shader, long commandBuffer, int groupCountX, int groupCountY,
                                      int groupCountZ, long fence, MemoryStack stack) {
      VkCommandBuffer vkCmdBuf = new VkCommandBuffer(commandBuffer, vulkanContext.device);
      vkResetCommandBuffer(vkCmdBuf, VK_COMMAND_BUFFER_LEVEL_PRIMARY);

      VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
              .sType$Default()
              .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

      int err = vkBeginCommandBuffer(vkCmdBuf, beginInfo);
      if (err != VK_SUCCESS) {
        throw new RuntimeException("Failed to begin command buffer: " + err);
      }

      vkCmdBindPipeline(vkCmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, shader.pipeline);
      vkCmdBindDescriptorSets(vkCmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, shader.pipelineLayout,
              0, stack.longs(shader.descriptorSet), null);

      vkCmdDispatch(vkCmdBuf, groupCountX, groupCountY, groupCountZ);

      err = vkEndCommandBuffer(vkCmdBuf);
      if (err != VK_SUCCESS) {
        throw new RuntimeException("Failed to end command buffer: " + err);
      }

      VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
              .sType$Default()
              .pCommandBuffers(stack.pointers(commandBuffer));

      err = vkQueueSubmit(new VkQueue(vulkanContext.computeQueue, vulkanContext.device), submitInfo, fence);
      if (err != VK_SUCCESS) {
        throw new RuntimeException("Failed to submit compute queue: " + err);
      }
    }

    public long getFence() {
      return fences[frameIndex];
    }

    public long getCommandBuffer() {
      return commandBuffers[frameIndex];
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
    private void increment() {
      numThreads.incrementAndGet();
    }
    private void decrement() {
      numThreads.decrementAndGet();
    }
  } public void createDescriptorPool(MemoryStack stack, int sets) {
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