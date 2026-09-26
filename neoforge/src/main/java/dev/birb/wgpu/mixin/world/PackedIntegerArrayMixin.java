package dev.birb.wgpu.mixin.world;

import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The geometry of a section's bit-packed storage, for the Rust copy of it.
 *
 * <p>The Fabric port called this type {@code PackedIntegerArray}; 26.1 renamed it to
 * {@code SimpleBitStorage} and kept the fields, so the mixin kept its name and lost its body. It
 * used to shadow the fields it never read - which is why the access transformer also lists them -
 * but an access transformer only widens access at run time, and this file is compiled against the
 * real (private) field, so the accessors are what actually reads them.
 *
 * <p>{@code WgpuNative.createPaletteStorage} takes exactly these five numbers beside the raw longs:
 * with them the Rust {@code PackedIntegerArray} finds the same long and the same bits that
 * Minecraft's own {@code get} would, which is the whole point of copying it rather than
 * re-deriving it.
 */
@Mixin(SimpleBitStorage.class)
public interface PackedIntegerArrayMixin {

    @Accessor("valuesPerLong")
    int wgpu_mc$valuesPerLong();

    @Accessor("mask")
    long wgpu_mc$mask();

    @Accessor("divideMul")
    int wgpu_mc$divideMul();

    @Accessor("divideAdd")
    int wgpu_mc$divideAdd();

    @Accessor("divideShift")
    int wgpu_mc$divideShift();
}