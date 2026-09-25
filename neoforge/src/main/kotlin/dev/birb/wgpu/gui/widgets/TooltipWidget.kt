package dev.birb.wgpu.gui.widgets

import dev.birb.wgpu.gui.WidgetRenderer
import dev.birb.wgpu.gui.options.Option
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import java.util.function.Supplier

/**
 * The box that explains the row under the mouse.
 *
 * It is laid out from its own content instead of from the row it belongs to: a short description
 * gets a small box, a long one wraps at [MAX_WIDTH], and the height follows the wrapped text. Where
 * it lands is decided against the region the screen confines it to ([confineTo]) - below its row
 * when there is room, above it when there is not, and never past that region's bottom, which is the
 * top of the button row.
 *
 * Both of those used to be wrong in a way that hid the end of the text: the box was always as wide
 * as the row and always began at the row's lower edge, so the last rows on a page put it under the
 * Apply button and off the bottom of the window, where the rest of the description was simply gone.
 * It now moves above the row when there is no room below, and the text is clipped to the box, so a
 * description too tall for the region ends at the box's edge rather than over the buttons.
 */
class TooltipWidget(private val hoveredOption: Supplier<Option<*>?>) : Widget(0, 0, 0, 0) {

    /** The row this tooltip is about, whose rectangle it is placed under or over. */
    private var anchor: Widget? = null

    /** The screen's limit for it: the settings list, from under the title to above the buttons. */
    private var regionLeft = 0
    private var regionTop = 0
    private var regionRight = 0
    private var regionBottom = 0

    private var option: Option<*>? = null
    private var animation = 0.0
    private var timer = 0.0

    /** The row the tooltip explains. Set every frame, from whichever row the mouse is over. */
    fun anchorTo(row: Widget) {
        anchor = row
    }

    /**
     * The area the tooltip may cover.
     *
     * `bottom` is the important one: it is the top of the button row, so nothing this widget draws
     * can cover the Apply button or run off the bottom of the window.
     */
    fun confineTo(left: Int, top: Int, right: Int, bottom: Int) {
        regionLeft = left
        regionTop = top
        regionRight = right
        regionBottom = bottom
    }

    override fun render(renderer: WidgetRenderer, mouseX: Int, mouseY: Int, delta: Float) {
        var opt = hoveredOption.get()

        // A row with nothing to explain gets no box. `tooltip` is never null - an option without a
        // description carries an empty component - so this is what stops the vanilla pages, whose
        // rows have no descriptions at all, from drawing an empty rectangle under the row.
        if (opt != null && opt.tooltip.string.isEmpty()) {
            opt = null
        }

        if (option == opt) timer += delta
        else {
            if (opt != null) animation = 0.0
            timer = 0.0
        }

        if (opt != null) option = opt
        else timer = 0.0

        if (timer >= 1.0 || (animation > 0 && option != null)) {
            animation = Mth.clamp(animation + delta * 6.0 * (if (opt != null) 1.0 else -1.0), 0.0, 1.0)

            if (animation > 0) {
                renderer.pushAlpha(animation)
                option?.let { render(renderer, it) }
                renderer.popAlpha()
            }
        }
    }

    private fun render(renderer: WidgetRenderer, option: Option<*>) {
        val row = anchor ?: return

        val wantsRestart = option.requiresRestart
        val restartHeight = if (wantsRestart) renderer.textHeight() + RESTART_GAP else 0

        // As wide as the content asks for, and no wider than the room there is for it. Without the
        // cap a description would be one very long line; with it the box is as narrow as its text
        // allows, which is what makes a short description look like a small box.
        val wantedWidth = maxOf(
            renderer.textWidth(option.tooltip),
            if (wantsRestart) renderer.textWidth(RESTART_TEXT) else 0,
        ) + PADDING * 2
        val maxWidth = (regionRight - regionLeft).coerceAtMost(MAX_WIDTH)
        width = wantedWidth.coerceIn(MIN_WIDTH.coerceAtMost(maxWidth), maxWidth)

        val wrapWidth = (width - PADDING * 2).coerceAtLeast(MIN_WRAP_WIDTH)
        val textHeight = renderer.wrappedTextHeight(option.tooltip, wrapWidth)
        val wantedHeight = textHeight + PADDING * 2 + restartHeight
        height = wantedHeight.coerceAtMost(regionBottom - regionTop)

        // Below the row if that fits where it may go, above it if that fits instead, and otherwise
        // as far down as it may start without leaving the region - which is where the clip below
        // does the rest.
        val below = row.y + row.height + GAP
        val above = row.y - GAP - height
        y = when {
            below + height <= regionBottom -> below
            above >= regionTop -> above
            else -> (regionBottom - height).coerceAtLeast(regionTop)
        }

        // Kept level with the row it describes, until that would push it out of the region - the
        // list is wider than the window at a large GUI scale, and a tooltip that ran off the right
        // edge was cut off there for the same reason it was cut off at the bottom.
        x = row.x.coerceIn(regionLeft, (regionRight - width).coerceAtLeast(regionLeft))

        // Background
        renderer.rect(x + 1, y + 1, x + width - 2, y + height - 2, ARGB.color(225, 0, 0, 0))

        // Outline
        renderer.rect(x, y, x + width, y + 1, Widget.ACCENT)
        renderer.rect(x, y + height - 1, x + width, y + height, Widget.ACCENT)
        renderer.rect(x, y + 1, x + 1, y + height - 1, Widget.ACCENT)
        renderer.rect(x + width - 1, y + 1, x + width, y + height - 1, Widget.ACCENT)

        // Text, and the restart line under it, inside the box: what does not fit is cut off at the
        // edge rather than drawn past the buttons. The paragraph is the part that gives way - it is
        // clipped to the space above the restart line, so the one line that says the setting needs a
        // restart survives even a description too tall for the box.
        val textBottom = (y + height - PADDING - restartHeight).coerceAtLeast(y + 1)

        renderer.enableScissor(x + 1, y + PADDING, x + width - 1, textBottom)

        renderer.wrappedText(option.tooltip, x + PADDING, y + PADDING, Widget.WHITE, wrapWidth)

        renderer.disableScissor()

        if (wantsRestart && height >= restartHeight + PADDING * 2) {
            renderer.text(
                RESTART_TEXT,
                x + PADDING,
                y + height - PADDING - renderer.textHeight(),
                Widget.RED,
            )
        }
    }

    companion object {
        /** Space between the box's edge and the text in it. */
        private const val PADDING = 5

        /** Space between the row and the box. */
        private const val GAP = 2

        /** Space between the description and the `* Requires restart` line. */
        private const val RESTART_GAP = 4

        /** A box at least this wide, so that a one-line description is still a box. */
        private const val MIN_WIDTH = 120

        /**
         * A box at most this wide.
         *
         * The cap is what makes the *content* decide the shape: the descriptions are paragraphs, so
         * without it the width would be the whole list and the height barely one line, which reads
         * as a tooltip that is mostly empty.
         */
        private const val MAX_WIDTH = 320

        /** The narrowest wrap the text is ever asked for, so a tiny region cannot degenerate. */
        private const val MIN_WRAP_WIDTH = 16

        private val RESTART_TEXT = Component.translatable("wgpu_mc.tooltip.requires_restart")
    }
}
