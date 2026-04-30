package dev.birb.wgpu.entity

import com.mojang.blaze3d.vertex.VertexConsumer

object DummyVertexConsumer : VertexConsumer {
    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer = this
    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer = this
    override fun setUv(u: Float, v: Float): VertexConsumer = this
    override fun setUv1(u: Int, v: Int): VertexConsumer = this
    override fun setUv2(u: Int, v: Int): VertexConsumer = this
    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer = this
}
