package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.WidgetRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.FastColor

abstract class Widget(x: Int, y: Int, var width: Int, var height: Int) {
    var x: Int = x
        protected set
    var y: Int = y
        protected set

    fun isMouseOver(mouseX: Double, mouseY: Double): Boolean {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
    }

    open fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean = false

    open fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = false

    open fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean = false

    open fun mouseMoved(mouseX: Double, mouseY: Double) {}

    abstract fun render(renderer: WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float)

    protected fun playClickSound() {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    protected fun centerY(height: Int): Int {
        return y + (this.height - height) / 2
    }

    protected fun centerTextY(renderer: WidgetRenderer): Int {
        return centerY(renderer.textHeight()) + 1
    }

    protected fun centerX(width: Int): Int {
        return x + (this.width - width) / 2
    }

    protected fun alignRight(width: Int, totalWidth: Int): Int {
        return x + totalWidth - width - 6
    }

    protected fun alignRight(width: Int): Int {
        return alignRight(width, this.width)
    }

    companion object {
        const val OPTION_WIDTH = 200
        const val DEFAULT_HEIGHT = 21

        @JvmField
        val BG = getColor(0, 0, 0, 125)

        @JvmField
        val BG_HOVERED = getColor(0, 0, 0, 175)

        @JvmField
        val WHITE = getColor(255, 255, 255, 255)

        @JvmField
        val ACCENT = getColor(225, 220, 144, 255)

        @JvmField
        val RED = getColor(225, 25, 25, 255)

        @JvmStatic
        fun getColor(r: Int, g: Int, b: Int, a: Int): Int {
            return FastColor.ARGB32.color(a, r, g, b)
        }
    }
}
