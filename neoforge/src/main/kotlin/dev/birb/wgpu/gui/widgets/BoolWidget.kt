package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.Utils
import dev.birb.wgpu.gui.WidgetRenderer
import dev.birb.wgpu.gui.options.BoolOption
import dev.birb.wgpu.gui.options.Option
import net.minecraft.util.Mth

class BoolWidget(x: Int, y: Int, width: Int, private val option: BoolOption) :
    Widget(x, y, width, Widget.DEFAULT_HEIGHT), IOptionWidget {

    private var animation: Double = if (option.get()) 1.0 else 0.0

    override fun getOption(): Option<*> = option

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (isMouseOver(mouseX, mouseY)) {
            option.set(!option.get())
            playClickSound()
            return true
        }
        return false
    }

    override fun render(renderer: WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        animation = Mth.clamp(animation + delta * 6.0 * (if (option.get()) 1.0 else -1.0), 0.0, 1.0)

        renderer.rect(x, y, x + width, y + height, if (isMouseOver(mouseX, mouseY)) Widget.BG_HOVERED else Widget.BG)
        renderer.text(option.getName(), x + 6, centerTextY(renderer), Widget.WHITE)

        val color = Utils.blendColors(Widget.ACCENT, Widget.WHITE, animation)
        val s = renderer.textHeight() + 2
        val boxX = alignRight(s)
        val boxY = centerY(s)

        // Frame
        renderer.rect(boxX, boxY, boxX + s, boxY + 1, color)
        renderer.rect(boxX, boxY + s - 1, boxX + s, boxY + s, color)
        renderer.rect(boxX, boxY + 1, boxX + 1, boxY + s - 1, color)
        renderer.rect(boxX + s - 1, boxY + 1, boxX + s, boxY + s - 1, color)

        // Middle
        if (animation > 0) {
            renderer.pushAlpha(animation)
            renderer.rect(boxX + 2, boxY + 2, boxX + s - 2, boxY + s - 2, Widget.ACCENT)
            renderer.popAlpha()
        }
    }
}
