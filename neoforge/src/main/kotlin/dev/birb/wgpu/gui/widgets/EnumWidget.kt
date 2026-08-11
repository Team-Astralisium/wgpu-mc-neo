package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.WidgetRenderer
import dev.birb.wgpu.gui.options.EnumOption
import dev.birb.wgpu.gui.options.Option
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import org.lwjgl.glfw.GLFW

class EnumWidget<T : Enum<T>>(x: Int, y: Int, width: Int, private val option: EnumOption<T>) :
    Widget(x, y, width, Widget.DEFAULT_HEIGHT), IOptionWidget {

    private var valueName: Component = option.formatter.apply(option.get())
    private var previousValueName: Component? = null
    private var animation: Double = 1.0

    override fun getOption(): Option<*> = option

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (isMouseOver(mouseX, mouseY)) {
            val direction = if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) 1 else -1
            option.set(option.cycle(direction))

            previousValueName = valueName
            valueName = option.formatter.apply(option.get())
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
        renderer.text(option.displayName(), x + 6, centerTextY(renderer), Widget.WHITE)

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
