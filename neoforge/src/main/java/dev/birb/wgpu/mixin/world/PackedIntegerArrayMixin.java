package dev.birb.wgpu.mixin.world;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.util.SimpleBitStorage")
public abstract class PackedIntegerArrayMixin {
    // TODO: remap direct-buffer storage hooks from Fabric PackedIntegerArray to SimpleBitStorage.
}