package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.WidgetRenderer
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import java.util.function.BooleanSupplier
import java.util.function.Supplier

class CustomButtonWidget(
    x: Int,
    y: Int,
    private val textSupplier: Supplier<Component>,
    width: Int,
    private val visible: BooleanSupplier,
    private val action: Runnable
) : Widget(x, y, width, Widget.DEFAULT_HEIGHT) {

    private var text: Component = textSupplier.get()
    private var previousText: Component? = null
    private var visibleAnimation: Double = if (visible.asBoolean) 1.0 else 0.0
    private var textAnimation: Double = 1.0

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (isMouseOver(mouseX, mouseY)) {
            action.run()
            playClickSound()
            return true
        }
        return false
    }

    override fun render(renderer: WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        visibleAnimation = Mth.clamp(visibleAnimation + delta * 6.0 * (if (visible.asBoolean) 1.0 else -1.0), 0.0, 1.0)

        if (visibleAnimation > 0) {
            renderer.pushAlpha(visibleAnimation)

            val t = textSupplier.get()
            if (text != t) {
                previousText = text
                text = t
                textAnimation = 0.0
            }
            textAnimation = Mth.clamp(textAnimation + delta * 6.0, 0.0, 1.0)

            // Background
            renderer.rect(x, y, x + width, y + height, if (isMouseOver(mouseX, mouseY)) Widget.BG_HOVERED else Widget.BG)

            // Label
            if (textAnimation < 1.0) {
                renderer.pushAlpha(1.0 - textAnimation)
                renderer.text(previousText!!, centerX(renderer.textWidth(previousText!!)), centerTextY(renderer), Widget.WHITE)
                renderer.popAlpha()
            }

            renderer.pushAlpha(textAnimation)
            renderer.text(text, centerX(renderer.textWidth(text)), centerTextY(renderer), Widget.WHITE)
            renderer.popAlpha()

            renderer.popAlpha()
        }
    }
}
