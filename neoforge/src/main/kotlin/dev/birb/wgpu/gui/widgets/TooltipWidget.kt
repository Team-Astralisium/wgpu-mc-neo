package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.WidgetRenderer
import dev.birb.wgpu.gui.options.Option
import net.minecraft.util.FastColor
import net.minecraft.util.Mth
import java.util.function.Supplier

class TooltipWidget(x: Int, y: Int, private val hoveredOption: Supplier<Option<*>?>) :
    Widget(x, y, OPTION_WIDTH, 0) {

    private var option: Option<*>? = null
    private var animation = 0.0
    private var timer = 0.0

    override fun render(renderer: WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        var opt = hoveredOption.get()
        if (opt != null && opt.tooltip == null) {
            opt = null
        }

        if (option == opt) timer += delta
        else {
            if (opt != null) animation = 0.0
            timer = 0.0
        }

        if (opt != null) option = opt
        else timer = 0.0

        if (timer >= 1.0 || (animation > 0 && option != null)) {
            animation = Mth.clamp(animation + delta * 6.0 * (if (opt != null) 1.0 else -1.0), 0.0, 1.0)

            if (animation > 0) {
                renderer.pushAlpha(animation)
                option?.let { render(renderer, it) }
                renderer.popAlpha()
            }
        }
    }

    private fun render(renderer: WidgetRenderer, option: Option<*>) {
        val tooltipHeight = renderer.wrappedTextHeight(option.tooltip, width - 10) + 10
        height = tooltipHeight

        if (option.requiresRestart) height += renderer.textHeight() + 4

        // Background
        renderer.rect(x + 1, y + 1, x + width - 2, y + height - 2, FastColor.ARGB32.color(225, 0, 0, 0))

        // Outline
        renderer.rect(x, y, x + width, y + 1, Widget.ACCENT)
        renderer.rect(x, y + height - 1, x + width, y + height, Widget.ACCENT)
        renderer.rect(x, y + 1, x + 1, y + height - 1, Widget.ACCENT)
        renderer.rect(x + width - 1, y + 1, x + width, y + height - 1, Widget.ACCENT)

        // Text
        renderer.wrappedText(option.tooltip, x + 5, y + 5, Widget.WHITE, width - 8)

        // Requires restart
        if (option.requiresRestart) renderer.text("* Requires restart", x + 5, y + tooltipHeight, Widget.RED)
    }
}
