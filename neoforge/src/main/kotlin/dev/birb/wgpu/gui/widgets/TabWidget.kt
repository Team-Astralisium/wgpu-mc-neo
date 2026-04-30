package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.OptionPageScreen
import dev.birb.wgpu.gui.OptionPages
import dev.birb.wgpu.gui.WidgetRenderer
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import java.util.function.BooleanSupplier

class TabWidget(
    x: Int,
    y: Int,
    private val page: OptionPages.Page,
    private val selected: BooleanSupplier
) : Widget(x, y, WIDTH, Widget.DEFAULT_HEIGHT + 4) {

    private var animation: Double = if (selected.asBoolean) 1.0 else 0.0

    companion object {
        const val WIDTH = 120
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (isMouseOver(mouseX, mouseY) && Minecraft.getInstance().screen is OptionPageScreen) {
            (Minecraft.getInstance().screen as OptionPageScreen).setCurrentPage(page)
            playClickSound()
            return true
        }
        return false
    }

    override fun render(renderer: WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        animation = Mth.clamp(animation + delta * 6.0 * (if (selected.asBoolean) 1.0 else -1.0), 0.0, 1.0)

        // Background
        renderer.rect(x, y, x + width, y + height, if (isMouseOver(mouseX, mouseY)) Widget.BG_HOVERED else Widget.BG)

        // Text
        renderer.text(page.name, x + 8, centerTextY(renderer), Widget.WHITE)

        // Selected
        if (animation > 0.0) {
            renderer.pushAlpha(animation)
            renderer.rect(x, y, x + 1, y + height, Widget.ACCENT)
            renderer.popAlpha()
        }
    }
}
