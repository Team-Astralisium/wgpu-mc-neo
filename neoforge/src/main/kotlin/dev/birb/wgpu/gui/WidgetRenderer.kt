package dev.birb.wgpu.gui

import it.unimi.dsi.fastutil.floats.FloatArrayList
import it.unimi.dsi.fastutil.floats.FloatStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.util.FastColor
import net.minecraft.util.FormattedCharSequence

class WidgetRenderer(private val context: GuiGraphics) {
    private val alphaStack: FloatStack = FloatArrayList()

    init {
        alphaStack.push(1.0f)
    }

    fun pushAlpha(alpha: Double) {
        alphaStack.push(alphaStack.peekFloat(0) * alpha.toFloat())
    }

    fun popAlpha() {
        alphaStack.popFloat()
    }

    fun rect(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        context.fill(x1, y1, x2, y2, applyAlpha(color))
    }

    fun text(text: String, x: Int, y: Int, color: Int) {
        context.drawString(font(), text, x, y, applyAlpha(color), false)
    }

    fun text(text: Component, x: Int, y: Int, color: Int) {
        context.drawString(font(), text, x, y, applyAlpha(color), false)
    }

    fun text(text: FormattedCharSequence, x: Int, y: Int, color: Int) {
        context.drawString(font(), text, x, y, applyAlpha(color), false)
    }

    fun wrappedText(text: Component, x: Int, y: Int, color: Int, maxWidth: Int) {
        val finalColor = applyAlpha(color)
        var currentY = y
        for (line in font().split(text, maxWidth)) {
            context.drawString(font(), line, x, currentY, finalColor, false)
            currentY += textHeight()
        }
    }

    fun wrappedTextHeight(text: Component, maxWidth: Int): Int {
        return font().split(text, maxWidth).size * textHeight()
    }

    fun trimText(text: FormattedText, width: Int): FormattedText {
        return font().substrByWidth(text, width)
    }

    fun textWidth(text: String): Int = font().width(text)

    fun textWidth(text: Component): Int = font().width(text)

    fun textHeight(): Int = font().lineHeight

    private fun applyAlpha(color: Int): Int {
        return FastColor.ARGB32.color(
            (FastColor.ARGB32.alpha(color) * alphaStack.peekFloat(0)).toInt(),
            FastColor.ARGB32.red(color),
            FastColor.ARGB32.green(color),
            FastColor.ARGB32.blue(color)
        )
    }

    private fun font() = Minecraft.getInstance().font
}
