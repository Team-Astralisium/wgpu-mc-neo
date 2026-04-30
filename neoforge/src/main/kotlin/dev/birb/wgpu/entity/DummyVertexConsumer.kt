package dev.birb.wgpu.entity

import net.minecraft.client.renderer.VertexConsumer

object DummyVertexConsumer : VertexConsumer {
    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer = this
    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer = this
    override fun setUv(u: Float, v: Float): VertexConsumer = this
    override fun setOverlayCoords(u: Int, v: Int): VertexConsumer = this
    override fun setLight(u: Int, v: Int): VertexConsumer = this
    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer = this
}
