package dev.birb.wgpu

import net.minecraft.util.ARGB

object Utils {
    @JvmStatic
    fun blendColors(color1: Int, color2: Int, amount: Double): Int {
        val r = (ARGB.red(color1) * amount + ARGB.red(color2) * (1 - amount)).toInt()
        val g = (ARGB.green(color1) * amount + ARGB.green(color2) * (1 - amount)).toInt()
        val b = (ARGB.blue(color1) * amount + ARGB.blue(color2) * (1 - amount)).toInt()
        val a = (ARGB.alpha(color1) * amount + ARGB.alpha(color2) * (1 - amount)).toInt()
        return ARGB.color(a, r, g, b)
    }
}
