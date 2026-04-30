package dev.birb.wgpu.backend

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.vertex.VertexFormat
import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.client.renderer.RenderType
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.function.Supplier

class WgpuRenderPass(val target: WgpuTexture?, val depth: WgpuTexture?) : RenderPass {

    val commands: ByteBuffer = MemoryUtil.memCalloc(16000)
    var commandCount: Int = 0

    companion object {
        private val COMMAND_SIZE = WgpuNative.getRenderPassCommandSize().toInt()
    }

    override fun pushDebugGroup(supplier: Supplier<String>) {}

    override fun popDebugGroup() {}

    override fun setPipeline(pipeline: RenderPipeline) {
        var pipelineId = 0

        val p = commands.position()

        commands.putLong(4)
        commands.position(p + COMMAND_SIZE)

        if (pipeline == RenderType.guiTextured()) pipelineId = 1

        commands.putInt(pipelineId)
    }

    override fun bindSampler(name: String, texture: GpuTexture?) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val nameStr = MemoryUtil.memCalloc(nameBytes.size)
        nameStr.put(nameBytes)

        val p = commands.position()

        commands.putLong(5)
        commands.putLong((texture as? WgpuTexture)?.texture ?: 0L)
        commands.putLong(MemoryUtil.memAddress0(nameStr))
        commands.putInt(nameBytes.size)

        commands.position(p + COMMAND_SIZE)
    }

    override fun setUniform(name: String, buffer: GpuBuffer) {
        setUniform(name, buffer.slice())
    }

    override fun setUniform(name: String, slice: GpuBufferSlice) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val nameStr = MemoryUtil.memCalloc(nameBytes.size)
        nameStr.put(nameBytes)

        val p = commands.position()

        commands.putLong(6)
        commands.putLong((slice.buffer() as WgpuBuffer).getWgpuBuffer())
        commands.putLong(MemoryUtil.memAddress0(nameStr))
        commands.putInt(nameBytes.size)
        commands.putInt(slice.offset())
        commands.putInt(slice.length() + slice.offset())

        commands.position(p + COMMAND_SIZE)
    }

    override fun enableScissor(x: Int, y: Int, width: Int, height: Int) {}

    override fun disableScissor() {}

    override fun setVertexBuffer(index: Int, buffer: GpuBuffer) {
        val p = commands.position()

        commands.putLong(3)
        commands.putLong((buffer as WgpuBuffer).getWgpuBuffer())
        commands.putInt(index)

        commands.position(p + COMMAND_SIZE)
        commandCount++
    }

    override fun setIndexBuffer(indexBuffer: GpuBuffer, indexType: VertexFormat.IndexType) {
        val i = when (indexType) {
            VertexFormat.IndexType.SHORT -> 0
            VertexFormat.IndexType.INT -> 1
        }

        val p = commands.position()

        commands.putLong(2)
        commands.putLong((indexBuffer as WgpuBuffer).getWgpuBuffer())
        commands.putInt(i)

        commands.position(p + COMMAND_SIZE)
        commandCount++
    }

    override fun drawIndexed(offset: Int, count: Int, primcount: Int, i: Int) {
        val p = commands.position()

        commands.putLong(1)
        commands.putInt(offset)
        commands.putInt(count)
        commands.putInt(primcount)
        commands.putInt(i)

        commands.position(p + COMMAND_SIZE)
        commandCount++
    }

    override fun drawMultipleIndexed(
        objects: Collection<RenderObject>,
        buffer: GpuBuffer?,
        indexType: VertexFormat.IndexType?,
        validationSkippedUniforms: Collection<String>
    ) {}

    override fun draw(offset: Int, count: Int) {
        val p = commands.position()

        commands.putLong(0)
        commands.putInt(offset)
        commands.putInt(count)

        commands.position(p + COMMAND_SIZE)
        commandCount++
    }

    override fun close() {}
}
