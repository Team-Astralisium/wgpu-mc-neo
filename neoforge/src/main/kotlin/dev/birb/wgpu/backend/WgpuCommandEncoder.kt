package dev.birb.wgpu.backend

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.textures.GpuTexture
import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.client.renderer.texture.NativeImage
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.*
import java.util.function.Supplier

class WgpuCommandEncoder : CommandEncoder {

    private val renderPasses: MutableList<WgpuRenderPass> = ArrayList()
    val mappedBuffers: MutableSet<WgpuBuffer> = HashSet()

    init {
        encoders.add(this)
    }

    override fun createRenderPass(supplier: Supplier<String>, colorAttachment: GpuTexture, optionalInt: OptionalInt): RenderPass {
        val pass = WgpuRenderPass(colorAttachment as WgpuTexture, null)
        renderPasses.add(pass)
        return pass
    }

    override fun createRenderPass(
        supplier: Supplier<String>,
        colorAttachment: GpuTexture,
        optionalInt: OptionalInt,
        depthAttachment: GpuTexture?,
        optionalDouble: OptionalDouble
    ): RenderPass {
        val pass = WgpuRenderPass(colorAttachment as WgpuTexture, depthAttachment as? WgpuTexture)
        renderPasses.add(pass)
        return pass
    }

    override fun clearColorTexture(texture: GpuTexture, color: Int) {}

    override fun clearColorAndDepthTextures(colorAttachment: GpuTexture, color: Int, depthAttachment: GpuTexture, depth: Double) {}

    override fun clearColorAndDepthTextures(
        colorAttachment: GpuTexture,
        color: Int,
        depthAttachment: GpuTexture,
        depth: Double,
        scissorX: Int,
        scissorY: Int,
        scissorWidth: Int,
        scissorHeight: Int
    ) {}

    override fun clearDepthTexture(texture: GpuTexture, depth: Double) {}

    override fun writeToBuffer(slice: GpuBufferSlice, source: ByteBuffer) {}

    override fun mapBuffer(buffer: GpuBuffer, read: Boolean, write: Boolean): GpuBuffer.MappedView {
        if (write) mappedBuffers.add(buffer as WgpuBuffer)
        val buf = (buffer as WgpuBuffer).map
        return WgpuBuffer.WgpuMappedView(buf.slice(0, buf.capacity()))
    }

    override fun mapBuffer(slice: GpuBufferSlice, read: Boolean, write: Boolean): GpuBuffer.MappedView {
        if (write) mappedBuffers.add(slice.buffer() as WgpuBuffer)
        val buffer = slice.buffer() as WgpuBuffer
        return WgpuBuffer.WgpuMappedView(buffer.map.slice(slice.offset(), slice.length()))
    }

    override fun writeToTexture(target: GpuTexture, source: NativeImage) {}

    override fun writeToTexture(
        target: GpuTexture,
        source: NativeImage,
        mipLevel: Int,
        intoX: Int,
        intoY: Int,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ) {}

    override fun writeToTexture(
        target: GpuTexture,
        source: IntBuffer,
        format: NativeImage.Format,
        mipLevel: Int,
        intoX: Int,
        intoY: Int,
        width: Int,
        height: Int
    ) {}

    override fun copyTextureToBuffer(
        target: GpuTexture,
        source: GpuBuffer,
        offset: Int,
        dataUploadedCallback: Runnable,
        mipLevel: Int
    ) {}

    override fun copyTextureToBuffer(
        target: GpuTexture,
        source: GpuBuffer,
        offset: Int,
        dataUploadedCallback: Runnable,
        mipLevel: Int,
        intoX: Int,
        intoY: Int,
        width: Int,
        height: Int
    ) {}

    override fun copyTextureToTexture(
        target: GpuTexture,
        source: GpuTexture,
        mipLevel: Int,
        intoX: Int,
        intoY: Int,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int
    ) {}

    override fun presentTexture(texture: GpuTexture) {
        submitAllEncoders()
        WgpuNative.presentTexture((texture as WgpuTexture).texture)
    }

    override fun createFence(): GpuBuffer.GpuFence? = null

    companion object {
        private var encoders: MutableList<WgpuCommandEncoder> = ArrayList()

        private fun submitAllEncoders() {
            val oldEncoders = encoders
            val toSubmit = MemoryUtil.memCalloc(oldEncoders.size * 32)
            encoders = ArrayList()

            for (encoder in oldEncoders) {
                val renderPassQueue = MemoryUtil.memCalloc(32 * encoder.renderPasses.size)
                val buffersToWrite = MemoryUtil.memCalloc(encoder.mappedBuffers.size * 16)

                for (pass in encoder.renderPasses) {
                    renderPassQueue.putLong(pass.target?.texture ?: 0L)
                    renderPassQueue.putLong(pass.depth?.texture ?: 0L)
                    renderPassQueue.putLong(MemoryUtil.memAddress0(pass.commands))
                    renderPassQueue.putInt(pass.commandCount)

                    // Padding
                    renderPassQueue.position(renderPassQueue.position() + 4)
                }

                for (buffer in encoder.mappedBuffers) {
                    buffersToWrite.putLong(buffer.getWgpuBuffer())
                    buffersToWrite.putLong(MemoryUtil.memAddress0(buffer.map))
                }

                toSubmit.putLong(MemoryUtil.memAddress0(renderPassQueue))
                toSubmit.putLong(encoder.renderPasses.size.toLong())
                toSubmit.putLong(MemoryUtil.memAddress0(buffersToWrite))
                toSubmit.putLong(encoder.mappedBuffers.size.toLong())
            }

            WgpuNative.submitEncoders(MemoryUtil.memAddress0(toSubmit), oldEncoders.size)
        }
    }
}
