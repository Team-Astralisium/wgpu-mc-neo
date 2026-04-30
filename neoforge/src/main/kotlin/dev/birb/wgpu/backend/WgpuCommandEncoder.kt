package dev.birb.wgpu.backend

import dev.birb.wgpu.rust.WgpuNative
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.HashSet

class WgpuCommandEncoder : AutoCloseable {

    private val renderPasses: MutableList<WgpuRenderPass> = ArrayList()
    val mappedBuffers: MutableSet<WgpuBuffer> = HashSet()

    init {
        encoders.add(this)
    }

    fun createRenderPass(colorAttachment: WgpuTexture, depthAttachment: WgpuTexture? = null): WgpuRenderPass {
        val pass = WgpuRenderPass(colorAttachment, depthAttachment)
        return pass.also(renderPasses::add)
    }

    fun mapBuffer(buffer: WgpuBuffer, write: Boolean = false): WgpuBuffer.WgpuMappedView {
        if (write) {
            mappedBuffers.add(buffer)
        }
        return buffer.mappedView()
    }

    fun mapBuffer(buffer: WgpuBuffer, offset: Int, length: Int, write: Boolean = false): WgpuBuffer.WgpuMappedView {
        if (write) {
            mappedBuffers.add(buffer)
        }
        return buffer.mappedView(offset, length)
    }

    fun presentTexture(texture: WgpuTexture) {
        submitAllEncoders()
        WgpuNative.presentTexture(texture.texture)
    }

    override fun close() {}

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
