package dev.birb.wgpu.gui.options

import dev.birb.wgpu.gui.widgets.TextEnumWidget
import dev.birb.wgpu.gui.widgets.Widget
import net.minecraft.network.chat.Component
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

class TextEnumOption(
    name: Component,
    tooltip: Component,
    requiresRestart: Boolean,
    getter: Supplier<Int>,
    setter: Consumer<Int>,
    private val values: Array<String>
) : Option<Int>(name, tooltip, requiresRestart, getter, setter) {

    fun cycle(direction: Int): Int {
        var index = get()
        index += direction
        while (index < 0) {
            index += values.size
        }
        index %= values.size
        set(index)
        return index
    }

    override fun createWidget(x: Int, y: Int, width: Int): Widget {
        return TextEnumWidget(x, y, width, this)
    }

    companion object {
        val FORMATTER = Function<TextEnumOption, Component> { option -> Component.literal(option.values[option.get()]) }
    }
}
