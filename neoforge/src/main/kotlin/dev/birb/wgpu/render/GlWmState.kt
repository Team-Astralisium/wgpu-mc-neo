package dev.birb.wgpu.render

import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.HashMap

object GlWmState {
    val generatedTextures: MutableList<WmTexture> = ArrayList()
    val textureSlots: MutableMap<Int, Int> = HashMap()
    val pixelStore: MutableMap<Int, Int> = HashMap()

    var activeTexture: Int = 0

    class WmTexture(var buffer: ByteBuffer? = null) {
        var width: Int = 0
        var height: Int = 0
    }
}
