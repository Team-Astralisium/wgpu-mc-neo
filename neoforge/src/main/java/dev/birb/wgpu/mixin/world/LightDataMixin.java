package dev.birb.wgpu.mixin.world;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData")
public abstract class LightDataMixin {
    // TODO: remap light packet internals once chunk-light upload path is enabled on NeoForge.
}