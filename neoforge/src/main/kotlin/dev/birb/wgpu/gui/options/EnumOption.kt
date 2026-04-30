package dev.birb.wgpu.gui.options

import dev.birb.wgpu.gui.widgets.EnumWidget
import dev.birb.wgpu.gui.widgets.Widget
import net.minecraft.network.chat.Component
import java.util.ArrayList
import java.util.EnumSet
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

class EnumOption<T : Enum<T>>(
    name: Component,
    enumClass: Class<T>,
    tooltip: Component,
    requiresRestart: Boolean,
    getter: Supplier<T>,
    setter: Consumer<T>,
    formatter: Function<T, Component> = Function { t -> Component.literal(t.toString()) }
) : Option<T>(name, tooltip, requiresRestart, getter, setter) {

    val formatter: Function<T, Component> = formatter
    private val values: List<T> = ArrayList(EnumSet.allOf(enumClass))

    fun cycle(direction: Int): T {
        for (i in values.indices) {
            if (values[i] == get()) {
                var newIndex = i + direction

                if (newIndex >= values.size) newIndex = 0
                else if (newIndex < 0) newIndex = values.size - 1

                return values[newIndex]
            }
        }

        throw IllegalStateException("This should never happen")
    }

    override fun createWidget(x: Int, y: Int, width: Int): Widget {
        return EnumWidget(x, y, width, this)
    }

    class Builder<T : Enum<T>>(private val enumClass: Class<T>) : Option.Builder<Builder<T>, T>() {
        private var formatter: Function<T, Component> = Function { t -> Component.literal(t.toString()) }

        fun setFormatter(formatter: Function<T, Component>): Builder<T> {
            this.formatter = formatter
            return this
        }

        override fun build(): Option<T> {
            return EnumOption(name!!, enumClass, tooltip!!, requiresRestart, getter!!, setter!!, formatter)
        }
    }
}
