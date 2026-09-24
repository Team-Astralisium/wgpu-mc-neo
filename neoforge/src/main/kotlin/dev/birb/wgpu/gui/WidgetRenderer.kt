package dev.birb.wgpu.gui

import it.unimi.dsi.fastutil.floats.FloatArrayList
import it.unimi.dsi.fastutil.floats.FloatStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence

/**
 * Immediate-mode drawing helper on top of 26.1's [GuiGraphicsExtractor].
 *
 * 26.1 replaced the old `GuiGraphics#drawString` API with
 * `GuiGraphicsExtractor#text` / `#textWithWordWrap`, so the text helpers below
 * delegate to those instead of walking `Font#split` by hand.
 */
class WidgetRenderer(private val context: GuiGraphicsExtractor) {
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
        context.text(font(), text, x, y, applyAlpha(color), false)
    }

    fun text(text: Component, x: Int, y: Int, color: Int) {
        context.text(font(), text, x, y, applyAlpha(color), false)
    }

    fun text(text: FormattedCharSequence, x: Int, y: Int, color: Int) {
        context.text(font(), text, x, y, applyAlpha(color), false)
    }

    fun wrappedText(text: Component, x: Int, y: Int, color: Int, maxWidth: Int) {
        context.textWithWordWrap(font(), text, x, y, maxWidth, applyAlpha(color))
    }

    fun wrappedTextHeight(text: Component, maxWidth: Int): Int {
        return font().wordWrapHeight(text, maxWidth)
    }

    fun trimText(text: FormattedText, width: Int): FormattedText {
        return font().substrByWidth(text, width)
    }

    fun textWidth(text: String): Int = font().width(text)

    fun textWidth(text: Component): Int = font().width(text)

    fun textHeight(): Int = font().lineHeight

    private fun applyAlpha(color: Int): Int {
        return ARGB.color(
            (ARGB.alpha(color) * alphaStack.peekFloat(0)).toInt(),
            ARGB.red(color),
            ARGB.green(color),
            ARGB.blue(color)
        )
    }

    private fun font() = Minecraft.getInstance().font
}
