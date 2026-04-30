package dev.birb.wgpu.backend

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.GpuDevice
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.TextureFormat
import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.*
import java.nio.ByteBuffer
import java.util.function.BiFunction
import java.util.function.Supplier

class WgpuBackend(window: Long, shaderSourceGetter: BiFunction<ResourceLocation, ShaderType, String>) : GpuDevice {

    private val minUniformOffsetAlignment: Int
    private val maxTextureSize: Int
    private val defaultShaderSourceGetter: BiFunction<ResourceLocation, ShaderType, String> = shaderSourceGetter

    init {
        val mcWindow = Minecraft.getInstance().window
        val w = mcWindow.width
        val h = mcWindow.height

        val nativeWindow = when (GLFW.glfwGetPlatform()) {
            GLFW.GLFW_PLATFORM_X11 -> GLFWNativeX11.glfwGetX11Window(window)
            GLFW.GLFW_PLATFORM_WIN32 -> GLFWNativeWin32.glfwGetWin32Window(window)
            GLFW.GLFW_PLATFORM_COCOA -> GLFWNativeCocoa.glfwGetCocoaWindow(window)
            GLFW.GLFW_PLATFORM_WAYLAND -> GLFWNativeWayland.glfwGetWaylandWindow(window)
            else -> throw IllegalStateException("Unexpected value: ${GLFW.glfwGetPlatform()}")
        }

        WgpuNative.createDevice(window, nativeWindow, w, h)

        this.minUniformOffsetAlignment = WgpuNative.getMinUniformAlignment()
        this.maxTextureSize = WgpuNative.getMaxTextureSize()
    }

    override fun createCommandEncoder(): CommandEncoder = WgpuCommandEncoder()

    override fun createTexture(
        labelGetter: Supplier<String>?,
        usage: Int,
        textureFormat: TextureFormat,
        height: Int,
        mipLevels: Int,
        j: Int
    ): GpuTexture {
        return this.createTexture(labelGetter!!.get(), usage, textureFormat, j, height, mipLevels)
    }

    override fun createTexture(
        label: String?,
        usage: Int,
        textureFormat: TextureFormat,
        width: Int,
        height: Int,
        mipLevels: Int
    ): GpuTexture {
        return WgpuTexture(usage, label, textureFormat, width, height, mipLevels)
    }

    override fun createBuffer(labelGetter: Supplier<String>?, usage: Int, size: Int): GpuBuffer {
        val label = labelGetter!!.get()
        return WgpuBuffer(label ?: "<mc buffer>", usage, size)
    }

    override fun createBuffer(labelGetter: Supplier<String>?, usage: Int, data: ByteBuffer): GpuBuffer {
        val label = labelGetter!!.get()
        return WgpuBuffer(label, usage, data)
    }

    override fun getImplementationInformation(): String = "wgpu"

    override fun getLastDebugMessages(): List<String> = emptyList()

    override fun isDebuggingEnabled(): Boolean = false

    override fun getVendor(): String = "wgpu"

    override fun getBackendName(): String = "wgpu"

    override fun getVersion(): String = "22"

    override fun getRenderer(): String = "wgpu-mc"

    override fun getMaxTextureSize(): Int = maxTextureSize

    override fun getUniformOffsetAlignment(): Int = minUniformOffsetAlignment

    override fun precompilePipeline(
        pipeline: RenderPipeline,
        sourceRetriever: BiFunction<ResourceLocation, ShaderType, String>?
    ): CompiledRenderPipeline = WgpuCompiledRenderPipeline()

    override fun clearPipelineCache() {}

    override fun getEnabledExtensions(): List<String> = emptyList()

    override fun close() {}
}
