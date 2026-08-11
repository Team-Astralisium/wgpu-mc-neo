package dev.birb.wgpu

import net.minecraft.util.FastColor

object Utils {
    @JvmStatic
    fun blendColors(color1: Int, color2: Int, amount: Double): Int {
        val r = (FastColor.ARGB32.red(color1) * amount + FastColor.ARGB32.red(color2) * (1 - amount)).toInt()
        val g = (FastColor.ARGB32.green(color1) * amount + FastColor.ARGB32.green(color2) * (1 - amount)).toInt()
        val b = (FastColor.ARGB32.blue(color1) * amount + FastColor.ARGB32.blue(color2) * (1 - amount)).toInt()
        val a = (FastColor.ARGB32.alpha(color1) * amount + FastColor.ARGB32.alpha(color2) * (1 - amount)).toInt()
        return FastColor.ARGB32.color(a, r, g, b)
    }
}
