package dev.birb.wgpu.gui.options

import dev.birb.wgpu.gui.widgets.FloatWidget
import dev.birb.wgpu.gui.widgets.Widget
import net.minecraft.network.chat.Component
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

class FloatOption(
    name: Component,
    tooltip: Component,
    requiresRestart: Boolean,
    getter: Supplier<Double>,
    setter: Consumer<Double>,
    val min: Double,
    val max: Double,
    val step: Double = 1.0,
    formatter: Function<Double, Component> = STANDARD_FORMATTER
) : Option<Double>(name, tooltip, requiresRestart, getter, setter) {

    val formatter: Function<Double, Component> = formatter

    override fun createWidget(x: Int, y: Int, width: Int): Widget {
        return FloatWidget(x, y, width, this)
    }

    class Builder : Option.Builder<Builder, Double>() {
        private var formatter: Function<Double, Component> = STANDARD_FORMATTER
        private var min: Double = 0.0
        private var max: Double = 0.0
        private var step: Double = 1.0

        fun setFormatter(formatter: Function<Double, Component>): Builder {
            this.formatter = formatter
            return this
        }

        fun setRange(min: Double, max: Double): Builder {
            this.min = min
            this.max = max
            return this
        }

        fun setStep(step: Double): Builder {
            this.step = step
            return this
        }

        override fun build(): Option<Double> {
            return FloatOption(
                requireName(),
                resolveTooltip(),
                requiresRestart,
                requireGetter(),
                requireSetter(),
                min,
                max,
                step,
                formatter
            )
        }
    }

    companion object {
        val STANDARD_FORMATTER = Function<Double, Component> { fl -> Component.literal(fl.toString()) }
    }
}
