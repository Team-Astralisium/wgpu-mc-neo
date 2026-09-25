package dev.birb.wgpu.gui.options

import dev.birb.wgpu.gui.widgets.IntWidget
import dev.birb.wgpu.gui.widgets.Widget
import net.minecraft.network.chat.Component
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

class IntOption(
    name: Component,
    tooltip: Component,
    requiresRestart: Boolean,
    getter: Supplier<Int>,
    setter: Consumer<Int>,
    val min: Int,
    val max: Int,
    val step: Int = 1,
    formatter: Function<Int, Component> = STANDARD_FORMATTER,
    /**
     * The values the setting accepts, when something else owns them - a vanilla option does, and its
     * range and step are not the ones this side would guess. `null` leaves the slider on [min],
     * [max] and [step], which is all a setting this mod owns needs.
     */
    val slider: IntSlider? = null
) : Option<Int>(name, tooltip, requiresRestart, getter, setter) {

    val formatter: Function<Int, Component> = formatter

    override fun createWidget(x: Int, y: Int, width: Int): Widget {
        return IntWidget(x, y, width, this)
    }

    class Builder : Option.Builder<Builder, Int>() {
        private var formatter: Function<Int, Component> = STANDARD_FORMATTER
        private var min: Int = 0
        private var max: Int = 0
        private var step: Int = 1

        fun setFormatter(formatter: Function<Int, Component>): Builder {
            this.formatter = formatter
            return this
        }

        fun setRange(min: Int, max: Int): Builder {
            this.min = min
            this.max = max
            return this
        }

        fun setStep(step: Int): Builder {
            this.step = step
            return this
        }

        override fun build(): Option<Int> {
            return IntOption(
                requireName(),
                resolveTooltip(),
                requiresRestart,
                requireGetter(),
                requireSetter(),
                min,
                max,
                step,
                formatter,
                slider
            )
        }
    }

    companion object {
        val STANDARD_FORMATTER = Function<Int, Component> { integer -> Component.literal(integer.toString()) }
    }
}
