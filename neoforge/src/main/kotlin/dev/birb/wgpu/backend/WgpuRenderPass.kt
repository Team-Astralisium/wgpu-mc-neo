package dev.birb.wgpu.backend

import dev.birb.wgpu.rust.WgpuNative
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

enum class WgpuIndexType(val nativeId: Int) {
    SHORT(0),
    INT(1)
}

class WgpuRenderPass(val target: WgpuTexture?, val depth: WgpuTexture?) : AutoCloseable {

    val commands: ByteBuffer = MemoryUtil.memCalloc(16000)
    var commandCount: Int = 0

    companion object {
        private val COMMAND_SIZE = WgpuNative.getRenderPassCommandSize().toInt().coerceAtLeast(32)
    }

    private fun writeCommandHeader(opcode: Long): Int {
        val p = commands.position()
        commands.putLong(opcode)
        return p
    }

    private fun finalizeCommand(start: Int, countAsDraw: Boolean = false) {
        commands.position((start + COMMAND_SIZE).coerceAtMost(commands.capacity()))
        if (countAsDraw) {
            commandCount++
        }
    }

    fun setPipeline(pipelineId: Int) {
        val p = writeCommandHeader(4)
        commands.putInt(pipelineId)
        finalizeCommand(p)
    }

    fun bindSampler(name: String, texture: WgpuTexture?) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val nameStr = MemoryUtil.memCalloc(nameBytes.size)
        nameStr.put(nameBytes)

        val p = writeCommandHeader(5)
        commands.putLong(texture?.texture ?: 0L)
        commands.putLong(MemoryUtil.memAddress0(nameStr))
        commands.putInt(nameBytes.size)
        finalizeCommand(p)
    }

    fun setUniform(name: String, buffer: WgpuBuffer, offset: Int = 0, length: Int = buffer.size - offset) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val nameStr = MemoryUtil.memCalloc(nameBytes.size)
        nameStr.put(nameBytes)

        val p = writeCommandHeader(6)
        commands.putLong(buffer.getWgpuBuffer())
        commands.putLong(MemoryUtil.memAddress0(nameStr))
        commands.putInt(nameBytes.size)
        commands.putInt(offset)
        commands.putInt(offset + length)
        finalizeCommand(p)
    }

    fun setVertexBuffer(index: Int, buffer: WgpuBuffer) {
        val p = writeCommandHeader(3)
        commands.putLong(buffer.getWgpuBuffer())
        commands.putInt(index)
        finalizeCommand(p, countAsDraw = true)
    }

    fun setIndexBuffer(indexBuffer: WgpuBuffer, indexType: WgpuIndexType) {
        val p = writeCommandHeader(2)
        commands.putLong(indexBuffer.getWgpuBuffer())
        commands.putInt(indexType.nativeId)
        finalizeCommand(p, countAsDraw = true)
    }

    fun drawIndexed(offset: Int, count: Int, primcount: Int, i: Int) {
        val p = writeCommandHeader(1)
        commands.putInt(offset)
        commands.putInt(count)
        commands.putInt(primcount)
        commands.putInt(i)
        finalizeCommand(p, countAsDraw = true)
    }

    fun draw(offset: Int, count: Int) {
        val p = writeCommandHeader(0)
        commands.putInt(offset)
        commands.putInt(count)
        finalizeCommand(p, countAsDraw = true)
    }

    override fun close() {
        MemoryUtil.memFree(commands)
    }
}
