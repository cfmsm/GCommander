# GCommander

<img width="400" height="400" alt="gcommander" src="https://github.com/user-attachments/assets/f97bd1bc-9124-4f66-ae42-b6f1431323cf" />

**High-Level Vulkan Compute API for Java**

GCommander (GPU Commander) simplifies GPU compute in Java by wrapping Vulkan boilerplate. It enables high-performance GPU computations with minimal setup, providing automatic buffer management, shader compilation, and threaded execution.

---

## Features

* Easy GPU buffer management (`GBuffer`)
* Compute shader compilation from GLSL (`GCommand`)
* Threaded command execution (`GCommander`)
* Automatic descriptor, pipeline, and staging management
* Low boilerplate for Vulkan compute operations
* GPU-aware optimizations for Intel, AMD, NVIDIA, and Apple Silicon GPUs

---

## Getting Started

### Requirements

* Java 17+
* LWJGL 3 with Vulkan & Shaderc bindings
* Vulkan drivers for your platform

### Installation

Clone the repository and include it as a dependency in your project. (Example for Maven/Gradle to be added once released.)

---

## Quick Start

### Initialize GCommander

```java
GCommander g = new GCommander();
g.initialize(10); // Initialize Vulkan with 10 descriptor sets
```

### Create GPU Buffers

```java
GBuffer input = GBuffer.ofFloat(g, 1024);  // 1024 floats
GBuffer output = GBuffer.ofFloat(g, 1024);

// Upload data to GPU
input.upload(new float[]{1.0f, 2.0f, 3.0f});
```

### Create a Compute Shader

```java
String glsl = """
    #version 450
    layout(std430, set=0, binding=0) buffer InputBuf { float data[]; } inputBuf;
    layout(std430, set=0, binding=1) buffer OutputBuf { float data[]; } outputBuf;
    layout(local_size_x=64, local_size_y=1, local_size_z=1) in;

    void main() {
        uint idx = gl_GlobalInvocationID.x;
        outputBuf.data[idx] = inputBuf.data[idx] * 2.0;
    }
""";

GCommand shader = new GCommand(g, glsl);
```

### Execute Shader

```java
g.execute(shader, input, output, 16, 1, 1); // group counts
output.sync(); // Wait for GPU to finish
float[] results = output.read();
```

---

## Advanced Usage

* **Multiple Shaders:** Run several compute shaders in a single submission.
* **Staging Buffers:** Automatic handling for devices without mappable VRAM.

---

## Cleanup

```java
shader.close();
input.close();
output.close();
g.cleanup();
```

---

## Class Overview

| Class             | Description                                                         |
| ----------------- | ------------------------------------------------------------------- |
| `GCommander`      | Main context, handles Vulkan initialization and compute session     |
| `GCommand`        | Compile GLSL compute shaders and manage pipelines & descriptor sets |
| `GBuffer`         | GPU memory buffer with optional staging for host read/write         |
| `GCommandBuilder` | Generates GLSL headers and provides GPU-optimal parameters          |

---

## Examples

* Vector addition
* Matrix multiplication
* GPU parallel reductions


---

## License

GCommander is licensed under the **GNU Affero General Public License v3 (AGPL-3.0)**.  
See the [LICENSE](LICENSE) file for full details.

---
