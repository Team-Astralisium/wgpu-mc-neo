package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.WidgetRenderer
import dev.birb.wgpu.gui.options.IntOption
import dev.birb.wgpu.gui.options.Option
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText

class IntWidget(x: Int, y: Int, width: Int, private val option: IntOption) :
    Widget(x, y, width, Widget.DEFAULT_HEIGHT), IOptionWidget {

    private var dragging = false

    override fun getOption(): Option<*> = option

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (isMouseOver(mouseX, mouseY)) {
            dragging = true
            calculateValue(mouseX.toInt())
            return true
        }
        return false
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (dragging) {
            dragging = false
            return true
        }
        return false
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        if (dragging) calculateValue(mouseX.toInt())
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (dragging) {
            calculateValue(mouseX.toInt())
            return true
        }
        return false
    }

    private fun calculateValue(mouseX: Int) {
        var w = width / 2
        var mouseX = mouseX - x - w
        w -= 6

        if (mouseX < 0) option.set(option.min)
        else if (mouseX > w) option.set(option.max)
        else {
            val value = mouseX.toDouble() / w * (option.max - option.min) + option.min
            option.set((Math.round(value / option.step) * option.step).toInt())
        }
    }

    override fun render(renderer: WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        val hovered = isMouseOver(mouseX, mouseY) || dragging

        // Background
        renderer.rect(x, y, x + width, y + height, if (hovered) Widget.BG_HOVERED else Widget.BG)

        val halfWidth = width / 2

        // Name
        if (hovered && renderer.textWidth(option.displayName()) > width / 3) {
            val trimmed = FormattedText.composite(renderer.trimText(option.displayName(), width / 3), Component.literal("..."))
            renderer.text(Language.getInstance().getVisualOrder(trimmed), x + 6, centerTextY(renderer), Widget.WHITE)
        } else {
            renderer.text(option.displayName(), x + 6, centerTextY(renderer), Widget.WHITE)
        }

        // Value
        val valueText = if (hovered) Component.literal(option.get().toString()) else option.formatter.apply(option.get())
        renderer.text(valueText, alignRight(renderer.textWidth(valueText), if (hovered) halfWidth else width), centerTextY(renderer), Widget.WHITE)

        if (hovered) {
            // Track
            renderer.rect(x + halfWidth, centerY(1), x + width - 6, centerY(1) + 1, Widget.WHITE)

            // Handle
            val handleX = x + halfWidth + getHandleX()
            val h = renderer.textHeight() + 2
            renderer.rect(handleX, centerY(h), handleX + 3, centerY(h) + h, Widget.WHITE)
        }
    }

    private fun getHandleX(): Int {
        val delta = (option.get() - option.min).toDouble() / (option.max - option.min)
        return (delta * (width / 2 - 6)).toInt() - 1
    }
}
