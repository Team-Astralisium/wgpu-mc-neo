package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.WidgetRenderer
import net.minecraft.network.chat.Component

class TextWidget(x: Int, y: Int, width: Int, private val text: Component) :
    Widget(x, y, width, HEIGHT) {

    companion object {
        const val HEIGHT = Widget.DEFAULT_HEIGHT
    }

    override fun render(renderer: WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        // Background
        renderer.rect(x, y, x + width, y + height, Widget.BG)

        // Text
        renderer.text(text, centerX(renderer.textWidth(text)), centerTextY(renderer), Widget.WHITE)
    }
}
