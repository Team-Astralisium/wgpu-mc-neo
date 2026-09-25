package dev.birb.wgpu.gui.options

import net.minecraft.client.OptionInstance
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The values an integer setting accepts, and where one of them sits on a slider.
 *
 * A vanilla option knows both and this side does not: `framerateLimit` steps in tens over 10..260,
 * `simulationDistance` runs 5..32 on a machine with the memory for it, and `guiScale`'s maximum
 * depends on the size of the window. Inventing a range instead is what made those settings look like
 * they could not be adjusted: a click on the far left of the frame-rate row asked for 5 fps,
 * `OptionInstance.set` logged *"Illegal option value 5 for options.framerateLimit"* and put the
 * option back to its initial value, and the row went on showing the 5 that had been asked for.
 *
 * The option's own slider mapping is the thing that knows this, and it cannot be asked for directly:
 * `OptionInstance.SliderableValueSet` is package-private in `net.minecraft.client`. What *is* public
 * is `validateValue`, so the accepted values are found by asking it about every value in a range -
 * which is also the question that matters, since a value it rejects is exactly the value that gets
 * logged and thrown away.
 */
class IntSlider private constructor(private val accepted: IntArray) {

    val min: Int get() = accepted.first()
    val max: Int get() = accepted.last()

    /** How far apart two accepted values are, which is the grid a slider moves on. */
    val step: Int by lazy {
        var smallest = Int.MAX_VALUE
        for (index in 1 until accepted.size) {
            smallest = minOf(smallest, accepted[index] - accepted[index - 1])
        }
        if (smallest == Int.MAX_VALUE) 1 else smallest
    }

    /** The accepted value closest to [value], which is what keeps a slider on the option's grid. */
    fun snap(value: Int): Int {
        var closest = accepted[0]

        for (candidate in accepted) {
            if (abs(candidate - value) < abs(closest - value)) {
                closest = candidate
            }
        }

        return closest
    }

    /** The value a click at [fraction] of the track asks for. */
    fun at(fraction: Double): Int {
        val along = fraction.coerceIn(0.0, 1.0)
        return snap((min + along * (max - min)).roundToInt())
    }

    /** Where [value] sits between [min] and [max], which is where the handle is drawn. */
    fun fraction(value: Int): Double {
        if (max == min) {
            return 0.0
        }

        return ((snap(value) - min).toDouble() / (max - min)).coerceIn(0.0, 1.0)
    }

    companion object {
        /**
         * The highest value the search asks about.
         *
         * Every integer option this screen shows tops out well below it - the frame-rate limit's 260
         * is the largest - and a value set that accepts nothing in the range answers `null` rather
         * than a wrong range, which leaves the row on the range its builder was given.
         */
        private const val SEARCH_LIMIT = 1024

        /**
         * What [values] accepts, or `null` when it accepts nothing in the searched range.
         *
         * A value set may *clamp* rather than reject (`guiScale` does: its maximum is whatever the
         * window can show), and a clamped answer is not an accepted one - so the answer only counts
         * when it is the value that was asked about.
         */
        fun of(values: OptionInstance.ValueSet<Int>): IntSlider? {
            val accepted = (0..SEARCH_LIMIT).filter { candidate ->
                values.validateValue(candidate).map { it == candidate }.orElse(false)
            }

            if (accepted.isEmpty()) {
                return null
            }

            return IntSlider(accepted.toIntArray())
        }
    }
}
