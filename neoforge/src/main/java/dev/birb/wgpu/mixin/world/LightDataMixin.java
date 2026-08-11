package dev.birb.wgpu.mixin.world;

import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.BitSet;
import java.util.List;

@Mixin(ClientboundLightUpdatePacketData.class)
public abstract class LightDataMixin {
    @Shadow public abstract BitSet getSkyYMask();

    @Shadow public abstract BitSet getEmptySkyYMask();

    @Shadow public abstract List<byte[]> getSkyUpdates();

    @Shadow public abstract BitSet getBlockYMask();

    @Shadow public abstract BitSet getEmptyBlockYMask();

    @Shadow public abstract List<byte[]> getBlockUpdates();

    private int readerIndex;
}
