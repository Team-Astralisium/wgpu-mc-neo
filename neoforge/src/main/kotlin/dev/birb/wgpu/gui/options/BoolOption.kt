package dev.birb.wgpu.gui.options

import dev.birb.wgpu.gui.widgets.BoolWidget
import dev.birb.wgpu.gui.widgets.Widget
import net.minecraft.network.chat.Component
import java.util.function.Consumer
import java.util.function.Supplier

class BoolOption(
    name: Component,
    tooltip: Component,
    requiresRestart: Boolean,
    getter: Supplier<Boolean>,
    setter: Consumer<Boolean>
) : Option<Boolean>(name, tooltip, requiresRestart, getter, setter) {

    override fun createWidget(x: Int, y: Int, width: Int): Widget {
        return BoolWidget(x, y, width, this)
    }

    class Builder : Option.Builder<Builder, Boolean>() {
        override fun build(): Option<Boolean> {
            return BoolOption(name!!, tooltip!!, requiresRestart, getter!!, setter!!)
        }
    }
}
