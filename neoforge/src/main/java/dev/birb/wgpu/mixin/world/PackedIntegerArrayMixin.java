package dev.birb.wgpu.mixin.world;

import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SimpleBitStorage.class)
public abstract class PackedIntegerArrayMixin {
    @Shadow private int valuesPerLong;

    @Shadow private int divideMul;

    @Shadow private int divideAdd;

    @Shadow private int divideShift;

    @Shadow @Final private int bits;

    @Shadow @Final private long mask;

    @Shadow @Final private int size;

    @Shadow @Final private long[] data;
}
