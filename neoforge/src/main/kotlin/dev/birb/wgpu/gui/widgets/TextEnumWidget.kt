package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.WidgetRenderer
import dev.birb.wgpu.gui.options.Option
import dev.birb.wgpu.gui.options.TextEnumOption
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import org.lwjgl.glfw.GLFW

class TextEnumWidget(x: Int, y: Int, width: Int, private val option: TextEnumOption) :
    Widget(x, y, width, Widget.DEFAULT_HEIGHT), IOptionWidget {

    private var valueName: Component = TextEnumOption.FORMATTER.apply(option)
    private var previousValueName: Component? = null
    private var animation: Double = 1.0

    override fun getOption(): Option<*> = option

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (isMouseOver(mouseX, mouseY)) {
            val direction = if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) 1 else -1
            option.set(option.cycle(direction))

            previousValueName = valueName
            valueName = TextEnumOption.FORMATTER.apply(option)
            animation = 0.0

            playClickSound()
            return true
        }
        return false
    }

    override fun render(renderer: WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        animation = Mth.clamp(animation + delta * 6.0, 0.0, 1.0)

        // Background
        renderer.rect(x, y, x + width, y + height, if (isMouseOver(mouseX, mouseY)) Widget.BG_HOVERED else Widget.BG)

        // Name
        renderer.text(option.getName(), x + 6, centerTextY(renderer), Widget.WHITE)

        // Value
        if (animation < 1.0) {
            renderer.pushAlpha(1.0 - animation)
            renderer.text(previousValueName!!, alignRight(renderer.textWidth(previousValueName!!)), centerTextY(renderer), Widget.WHITE)
            renderer.popAlpha()
        }

        renderer.pushAlpha(animation)
        renderer.text(valueName, alignRight(renderer.textWidth(valueName)), centerTextY(renderer), Widget.WHITE)
        renderer.popAlpha()
    }
}
