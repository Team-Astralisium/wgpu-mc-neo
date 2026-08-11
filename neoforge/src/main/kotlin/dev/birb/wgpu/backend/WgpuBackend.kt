package dev.birb.wgpu.backend

import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.*
import java.nio.ByteBuffer
import java.util.function.BiFunction

class WgpuBackend(
    window: Long,
    @Suppress("UNUSED_PARAMETER") shaderSourceGetter: BiFunction<ResourceLocation, Any, String>? = null
) : AutoCloseable {

    val minUniformOffsetAlignment: Int
    val maxTextureSize: Int

    init {
        val mcWindow = Minecraft.getInstance().window
        val w = mcWindow.width
        val h = mcWindow.height
        val windowHandle = mcWindow.window

        val nativeWindow = when (GLFW.glfwGetPlatform()) {
            GLFW.GLFW_PLATFORM_X11 -> GLFWNativeX11.glfwGetX11Window(windowHandle)
            GLFW.GLFW_PLATFORM_WIN32 -> GLFWNativeWin32.glfwGetWin32Window(windowHandle)
            GLFW.GLFW_PLATFORM_COCOA -> GLFWNativeCocoa.glfwGetCocoaWindow(windowHandle)
            GLFW.GLFW_PLATFORM_WAYLAND -> GLFWNativeWayland.glfwGetWaylandWindow(windowHandle)
            else -> throw IllegalStateException("Unexpected value: ${GLFW.glfwGetPlatform()}")
        }

        WgpuNative.createDevice(window, nativeWindow, w, h)

        this.minUniformOffsetAlignment = WgpuNative.getMinUniformAlignment()
        this.maxTextureSize = WgpuNative.getMaxTextureSize()
    }

    fun createCommandEncoder(): WgpuCommandEncoder = WgpuCommandEncoder()

    fun createTexture(
        label: String?,
        usage: Int,
        textureFormat: WgpuTextureFormat,
        width: Int,
        height: Int,
        mipLevels: Int
    ): WgpuTexture {
        return WgpuTexture(usage, label, textureFormat, width, height, mipLevels)
    }

    fun createBuffer(label: String, usage: Int, size: Int): WgpuBuffer {
        return WgpuBuffer(label, usage, size)
    }

    fun createBuffer(label: String, usage: Int, data: ByteBuffer): WgpuBuffer {
        return WgpuBuffer(label, usage, data)
    }

    fun implementationInformation(): String = "wgpu"

    fun lastDebugMessages(): List<String> = emptyList()

    fun isDebuggingEnabled(): Boolean = false

    fun vendor(): String = "wgpu"

    fun backendName(): String = "wgpu"

    fun version(): String = "21.1-compat"

    fun renderer(): String = "wgpu-mc"

    fun uniformOffsetAlignment(): Int = minUniformOffsetAlignment

    fun precompilePipeline(): WgpuCompiledRenderPipeline = WgpuCompiledRenderPipeline()

    fun clearPipelineCache() {}

    override fun close() {}
}
