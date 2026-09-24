package dev.birb.wgpu.gui

import dev.birb.wgpu.gui.options.Option
import dev.birb.wgpu.gui.widgets.CustomButtonWidget
import dev.birb.wgpu.gui.widgets.IOptionWidget
import dev.birb.wgpu.gui.widgets.TabWidget
import dev.birb.wgpu.gui.widgets.TextWidget
import dev.birb.wgpu.gui.widgets.TooltipWidget
import dev.birb.wgpu.gui.widgets.Widget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth

class OptionPageScreen(private val parent: Screen) :
    Screen(Component.translatable("wgpu_mc.screen.video_options")) {

    companion object {
        private const val MAX_WIDTH = 1000

        /** How far one wheel notch scrolls the list of settings, in GUI units. */
        private const val SCROLL_STEP = 12
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
    private var buttonRowY = 0

    /** How far down the list of settings is scrolled, in GUI units. */
    private var scroll = 0

    /** How far it *can* be scrolled: zero while every row fits between the title and the buttons. */
    private var maxScroll = 0

    fun switchPage(page: OptionPages.Page) {
        if (currentPage == page) return

        currentPage = page
        previousOptionWidgets.clear()
        previousOptionWidgets.addAll(optionWidgets)
        animation = 0.0
        scroll = 0
        init()
    }

    override fun init() {
        optionWidgets.clear()

        if (width != previousWidth || height != previousHeight) {
            widgets.clear()
            initOtherThanOptions()
        }

        val x = 8 + TabWidget.WIDTH + 8
        val top = 8 + TextWidget.HEIGHT + 8
        val optimalWidth = getOptimalWidth()

        // The list lives between the title and the button row, and a page can be taller than that:
        // the debug section on the Electrum page is what pushed it past the bottom, where the last
        // row used to end up underneath Apply with no way to reach it. Rows outside the viewport
        // are left out of the render rather than clipped, which is why a row is only drawn when it
        // fits whole.
        val bottom = buttonRowY - 4
        val contentHeight = layoutHeight(top)

        maxScroll = maxOf(0, contentHeight - bottom)
        scroll = scroll.coerceIn(0, maxScroll)

        var y = top - scroll
        for (group in currentPage) {
            for (entry in group) {
                if (y >= top && y + Widget.DEFAULT_HEIGHT <= bottom) {
                    val widget = entry.createWidget(alignX(x), y, optimalWidth - x - 8)

                    // A heading is laid out like an option and fades in with the page like one, but
                    // it is not an option: the hover and tooltip pass only ever sees the real ones.
                    if (entry is OptionPages.Entry.Setting) {
                        add(widget)
                    } else {
                        optionWidgets.add(widget)
                    }
                }

                y += Widget.DEFAULT_HEIGHT
            }
            y += 4
        }

        previousWidth = width
        previousHeight = height
    }

    /** Where the last row of the current page ends, laid out from [top] with nothing scrolled. */
    private fun layoutHeight(top: Int): Int {
        var y = top

        for (group in currentPage) {
            y += group.size * Widget.DEFAULT_HEIGHT + 4
        }

        return y
    }

    /**
     * Scrolls the list of settings, which only moves on a page tall enough to need it.
     *
     * The wheel is the only way to reach the settings below the fold, so a page that fits swallows
     * the event rather than passing it on - there is nothing underneath to scroll.
     */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }

        val next = (scroll - (scrollY * SCROLL_STEP).toInt()).coerceIn(0, maxScroll)
        if (next == scroll) {
            return true
        }

        scroll = next
        init()
        return true
    }

    private fun initOtherThanOptions() {
        val optimalWidth = getOptimalWidth()

        var x = 8
        var y = 8

        y += add(TextWidget(alignX(x), y, optimalWidth - 16, Component.translatable("wgpu_mc.screen.video_options"))).height + 8

        for (page in pages) {
            y += add(TabWidget(alignX(x), y, page) { page == currentPage }).height
        }

        tooltipWidget = add(TooltipWidget(0, 0) { hoveredOption })

        x = optimalWidth - 8
        y = height - 8 - Widget.DEFAULT_HEIGHT
        val buttonWidth = 100
        buttonRowY = y

        add(CustomButtonWidget(
            alignX(x - buttonWidth),
            y,
            { if (pages.isChanged()) Component.translatable("wgpu_mc.button.apply")
                else Component.translatable("wgpu_mc.button.close") },
            buttonWidth,
            { true },
            {
                if (pages.isChanged()) {
                    pages.apply()
                } else {
                    onClose()
                }
            }
        ))

        add(CustomButtonWidget(
            alignX(x - buttonWidth - 4 - buttonWidth),
            y,
            { Component.translatable("wgpu_mc.button.undo") },
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

    // 26.1 renamed Screen#render to Screen#extractRenderState. The background is *not* this
    // method's job: Screen#extractRenderStateWithTooltipAndSubtitles is final and already calls
    // extractBackground before delegating here. Calling it again drew the panorama and the menu
    // background twice - which is what turned the backdrop's contrast inside out - and with a
    // level loaded it threw "Can only blur once per frame" from extractBlurredBackground.
    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val optionWidget = getHoveredOptionWidget(mouseX, mouseY)
        if (optionWidget is IOptionWidget) {
            hoveredOption = optionWidget.getOption()
            tooltipWidget.setPosition(optionWidget.x, optionWidget.y + optionWidget.height)
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

        renderRestartNotice(renderer)
    }

    /**
     * Warns that some of what has been changed only takes effect on the next launch.
     *
     * The tooltip already marks each individual setting with "* Requires restart", but that only
     * shows up while hovering and disappears as soon as the value is applied. The renderer's
     * backend and vsync fall into this category, and a player who switched the backend and saw
     * nothing change would reasonably conclude the switch did nothing at all.
     */
    private fun renderRestartNotice(renderer: WidgetRenderer) {
        val message = when {
            pages.hasPendingRestartChanges() -> Component.translatable("wgpu_mc.notice.restart_pending")
            pages.hasAppliedRestartChanges() -> Component.translatable("wgpu_mc.notice.restart_applied")
            else -> return
        }

        val y = buttonRowY - renderer.textHeight() - 4
        val textWidth = renderer.textWidth(message)
        val x = alignX(getOptimalWidth() - 8 - textWidth)

        renderer.text(message, x, y, Widget.RED)
    }

    // 26.1 replaced the (double, double, int) mouse entry points on GuiEventListener
    // with MouseButtonEvent records.
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x()
        val mouseY = event.y()
        val button = event.button()

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
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val mouseX = event.x()
        val mouseY = event.y()
        val button = event.button()

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
        return super.mouseReleased(event)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val mouseX = event.x()
        val mouseY = event.y()
        val button = event.button()

        draggingWidget?.let {
            return it.mouseDragged(mouseX, mouseY, button, dragX, dragY)
        }
        return super.mouseDragged(event, dragX, dragY)
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

