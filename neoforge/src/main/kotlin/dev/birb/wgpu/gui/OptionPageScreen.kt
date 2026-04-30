package dev.birb.wgpu.gui

import dev.birb.wgpu.gui.options.Option
import dev.birb.wgpu.gui.widgets.CustomButtonWidget
import dev.birb.wgpu.gui.widgets.IOptionWidget
import dev.birb.wgpu.gui.widgets.TabWidget
import dev.birb.wgpu.gui.widgets.TextWidget
import dev.birb.wgpu.gui.widgets.TooltipWidget
import dev.birb.wgpu.gui.widgets.Widget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth

class OptionPageScreen(private val parent: Screen) : Screen(Component.literal("Options")) {

    companion object {
        private const val MAX_WIDTH = 1000
    }

    private val pages = OptionPages()
    var currentPage: OptionPages.Page = pages.getDefault()

    private val widgets: MutableList<Widget> = ArrayList()
    private val optionWidgets: MutableList<Widget> = ArrayList()
    private val previousOptionWidgets: MutableList<Widget> = ArrayList()

    private lateinit var tooltipWidget: TooltipWidget
    private var draggingWidget: Widget? = null
    private var hoveredOption: Option<*>? = null

    private var animation = 1.0
    private var previousWidth = 0
    private var previousHeight = 0

    fun setCurrentPage(page: OptionPages.Page) {
        if (currentPage == page) return

        currentPage = page
        previousOptionWidgets.clear()
        previousOptionWidgets.addAll(optionWidgets)
        animation = 0.0
        init()
    }

    override fun init() {
        optionWidgets.clear()

        if (width != previousWidth || height != previousHeight) {
            widgets.clear()
            initOtherThanOptions()
        }

        var x = 8 + TabWidget.WIDTH + 8
        var y = 8 + TextWidget.HEIGHT + 8
        val optimalWidth = getOptimalWidth()

        for (group in currentPage) {
            for (option in group) {
                add(option.createWidget(alignX(x), y, optimalWidth - x - 8))
                y += Widget.DEFAULT_HEIGHT
            }
            y += 4
        }

        previousWidth = width
        previousHeight = height
    }

    private fun initOtherThanOptions() {
        val optimalWidth = getOptimalWidth()

        var x = 8
        var y = 8

        y += add(TextWidget(alignX(x), y, optimalWidth - 16, Component.literal("Video Options"))).height + 8

        for (page in pages) {
            y += add(TabWidget(alignX(x), y, page) { page == currentPage }).height
        }

        tooltipWidget = add(TooltipWidget(0, 0) { hoveredOption })

        x = optimalWidth - 8
        y = height - 8 - Widget.DEFAULT_HEIGHT
        val buttonWidth = 100

        add(CustomButtonWidget(
            alignX(x - buttonWidth),
            y,
            { Component.literal(if (pages.isChanged()) "Apply and close" else "Close") },
            buttonWidth,
            { true },
            {
                pages.apply()
                onClose()
            }
        ))

        add(CustomButtonWidget(
            alignX(x - buttonWidth - 4 - buttonWidth),
            y,
            { Component.literal("Undo") },
            buttonWidth,
            { pages.isChanged() },
            { pages.undo() }
        ))
    }

    private fun getOptimalWidth(): Int {
        return (MAX_WIDTH / Minecraft.getInstance().window.guiScale).toInt().coerceAtMost(width)
    }

    private fun alignX(x: Int): Int {
        return x + (width - getOptimalWidth()) / 2
    }

    private fun getHoveredOptionWidget(mouseX: Int, mouseY: Int): Widget? {
        for (i in optionWidgets.size - 1 downTo 0) {
            val widget = optionWidgets[i]
            if (widget.isMouseOver(mouseX, mouseY)) {
                return widget
            }
        }
        return null
    }

    private fun <T : Widget> add(widget: T): T {
        if (widget is IOptionWidget) {
            optionWidgets.add(widget)
        } else {
            widgets.add(widget)
        }
        return widget
    }

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context, mouseX, mouseY, delta)

        val optionWidget = getHoveredOptionWidget(mouseX, mouseY)
        if (optionWidget is IOptionWidget) {
            hoveredOption = optionWidget.getOption()
            tooltipWidget.x = optionWidget.x
            tooltipWidget.y = optionWidget.y + optionWidget.height
            tooltipWidget.width = optionWidget.width
        } else {
            hoveredOption = null
        }

        val deltaSeconds = delta / 20.0f
        animation = Mth.clamp(animation + deltaSeconds * 6.0, 0.0, 1.0)

        val renderer = WidgetRenderer(context)
        if (animation < 1.0) {
            renderer.pushAlpha(1.0f - animation)
            for (widget in previousOptionWidgets) {
                widget.render(renderer, mouseX, mouseY, deltaSeconds)
            }
            renderer.popAlpha()
        }

        renderer.pushAlpha(animation)
        for (widget in optionWidgets) {
            widget.render(renderer, mouseX, mouseY, deltaSeconds)
        }
        renderer.popAlpha()

        for (widget in widgets) {
            widget.render(renderer, mouseX, mouseY, deltaSeconds)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        for (i in widgets.size - 1 downTo 0) {
            val widget = widgets[i]
            if (widget.mouseClicked(mouseX, mouseY, button)) {
                draggingWidget = widget
                return true
            }
        }
        for (i in optionWidgets.size - 1 downTo 0) {
            val widget = optionWidgets[i]
            if (widget.mouseClicked(mouseX, mouseY, button)) {
                draggingWidget = widget
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        draggingWidget?.let {
            val handled = it.mouseReleased(mouseX, mouseY, button)
            draggingWidget = null
            if (handled) return true
        }

        for (widget in widgets) {
            if (widget.mouseReleased(mouseX, mouseY, button)) {
                return true
            }
        }
        for (widget in optionWidgets) {
            if (widget.mouseReleased(mouseX, mouseY, button)) {
                return true
            }
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        draggingWidget?.let {
            return it.mouseDragged(mouseX, mouseY, button, dragX, dragY)
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        for (widget in widgets) {
            widget.mouseMoved(mouseX, mouseY)
        }
        for (widget in optionWidgets) {
            widget.mouseMoved(mouseX, mouseY)
        }
        super.mouseMoved(mouseX, mouseY)
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }
}
