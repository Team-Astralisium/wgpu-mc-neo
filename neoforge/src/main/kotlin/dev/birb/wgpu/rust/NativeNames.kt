package dev.birb.wgpu.rust

import dev.birb.wgpu.WgpuMcMod
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The NUL-terminated `char *` a name argument needs, encoded once per name and kept.
 *
 * Every name that crosses this ABI - buffer and texture labels, vertex element names, uniform and
 * sampler names - used to be encoded into a fresh `Arena.ofConfined()`, which was then closed as
 * soon as the call returned: an arena, an allocation, an encode and a close, for a string that is
 * the same UTF-8 every time the same name arrives. Buffers and textures are created while the game
 * runs, so that was the draw path paying for its own labels.
 *
 * The encoding now happens once per distinct name, in one arena that is never closed, and every
 * later call passes the pointer it already has. Native code reads a name during the call it was
 * passed to - it copies what it keeps - so handing out the same pointer repeatedly is safe.
 *
 * Nothing bounds the number of names, deliberately. A label is a `String` Minecraft wrote by hand
 * or derived from an asset name, and the few that carry a number carry one that is bounded too:
 * `"... animation frame {n}"` has one per unique frame of an animated sprite, `"UberBuffer ... {n}"`
 * one per heap a section buffer has grown into. The set is therefore a property of the loaded
 * assets, not of how long the game runs - a few thousand entries, tens of bytes each - and a cap
 * would be worse than useless: past it, a name that is not kept would be encoded again on every
 * call, which is the allocation this exists to remove. [reportWatermarks] is what turns that
 * argument into something the log can confirm.
 */
object NativeNames {
    /** How many names to go through before saying how many there are, and what the latest is. */
    private const val REPORT_EVERY = 4096

    /** Never closed: a pointer handed to native code stays valid for the life of the process. */
    private val arena: Arena = Arena.global()

    private val names = ConcurrentHashMap<String, MemorySegment>()

    /** The last watermark reported, so the count shows up in the log as it crosses each one. */
    private val reported = AtomicInteger()

    /** The UTF-8 encoding of [name] as a C string, with its terminating NUL. */
    @JvmStatic
    fun utf8(name: String): MemorySegment {
        names[name]?.let { return it }

        val segment = names.computeIfAbsent(name) { arena.allocateFrom(it) }
        reportWatermarks(names.size, name)
        return segment
    }

    private fun reportWatermarks(size: Int, name: String) {
        val mark = size / REPORT_EVERY

        if (mark > 0 && reported.getAndSet(mark) < mark) {
            WgpuMcMod.LOGGER.info(
                "wgpu: {} distinct names interned for the native ABI (latest: '{}'); this set is a " +
                    "property of the loaded assets, not of the running time",
                size,
                name,
            )
        }
    }

    /** How many distinct names have been interned, for diagnostics and tests. */
    @JvmStatic
    fun size(): Int = names.size
}
