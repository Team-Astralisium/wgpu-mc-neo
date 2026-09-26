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
    formatter: Function<T, Component> = Function { t -> Component.literal(t.toString()) },
    /**
     * The values this row cycles through, which is every constant of [enumClass] unless the caller
     * names a shorter list. A value the renderer cannot draw is left out here rather than offered and
     * then refused - and the settings file that names such a value is clamped before this row is
     * built, so what it shows is always one of these.
     */
    offeredValues: List<T>? = null
) : Option<T>(name, tooltip, requiresRestart, getter, setter) {

    val formatter: Function<T, Component> = formatter
    private val values: List<T> = offeredValues?.let { ArrayList(it) } ?: ArrayList(EnumSet.allOf(enumClass))

    fun cycle(direction: Int): T {
        if (values.isEmpty()) {
            return get()
        }

        for (i in values.indices) {
            if (values[i] == get()) {
                var newIndex = i + direction

                if (newIndex >= values.size) newIndex = 0
                else if (newIndex < 0) newIndex = values.size - 1

                return values[newIndex]
            }
        }

        // The value the game holds is not one this row offers, so there is nothing to step from. It
        // steps in from the end of the list in the direction asked for rather than throwing: a row is
        // drawn before it is clicked, and an exception here would take the game down over a setting
        // the player can see is on something the row does not list.
        return if (direction >= 0) values[0] else values[values.size - 1]
    }

    override fun createWidget(x: Int, y: Int, width: Int): Widget {
        return EnumWidget(x, y, width, this)
    }

    class Builder<T : Enum<T>>(private val enumClass: Class<T>) : Option.Builder<Builder<T>, T>() {
        private var formatter: Function<T, Component> = Function { t -> Component.literal(t.toString()) }
        private var offeredValues: List<T>? = null

        fun setFormatter(formatter: Function<T, Component>): Builder<T> {
            this.formatter = formatter
            return this
        }

        /** Offers [values] on this row instead of every constant of the enum, in this order. */
        fun setValues(values: List<T>): Builder<T> {
            this.offeredValues = values
            return this
        }

        override fun build(): Option<T> {
            return EnumOption(
                requireName(),
                enumClass,
                resolveTooltip(),
                requiresRestart,
                requireGetter(),
                requireSetter(),
                formatter,
                offeredValues
            )
        }
    }
}
