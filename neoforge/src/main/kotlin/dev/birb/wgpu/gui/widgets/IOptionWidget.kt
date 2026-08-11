package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.options.Option

interface IOptionWidget {
    fun getOption(): Option<*>
}
