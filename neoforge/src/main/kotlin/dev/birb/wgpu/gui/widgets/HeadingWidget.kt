package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.WidgetRenderer
import net.minecraft.network.chat.Component

/**
 * A sub-heading over the group of settings that follows it.
 *
 * It takes a row of the page's list like a setting does, which is what keeps the layout simple, but
 * it is not an option: it carries no value, takes no part in hover or tooltips, and is left out of
 * what Apply sends to the renderer. An [Component.empty] one is a blank row, which is how a section
 * is separated from the setting above it.
 */
class HeadingWidget(x: Int, y: Int, width: Int, private val text: Component) :
    Widget(x, y, width, HEIGHT) {

    companion object {
        const val HEIGHT = Widget.DEFAULT_HEIGHT
    }

    override fun render(renderer: WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        // No background row: a heading labels what is below it rather than being a thing of its
        // own, and a filled row would read as an option that does nothing when clicked.
        if (text.string.isEmpty()) {
            return
        }

        renderer.text(text, x + 6, centerTextY(renderer), Widget.ACCENT)
    }
}
