package dev.birb.wgpu.mixin.core;

import dev.birb.wgpu.rust.WgpuNative;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Collectors;

@Mixin(Registry.class)
public interface RegistryMixin {
    // 26.1 renamed ResourceLocation to Identifier, so the target descriptor changed.
    @Inject(method = "register(Lnet/minecraft/core/Registry;Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)Ljava/lang/Object;", at = @At("RETURN"))
    private static void registryHook(Registry<?> registry, Identifier id, Object entry, CallbackInfoReturnable<Object> cir) {
        if (entry instanceof Block block) {
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();

            WgpuNative.registerBlock(blockId);

            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                // 26.1 changed StateHolder#getValues() from a Map to a Stream<Property.Value<?>>,
                // and Property.Value is a record whose toString() is exactly "<name>=<valueName>".
                String stateKey = state.getValues()
                        .map(Object::toString)
                        .collect(Collectors.joining(","));
                WgpuNative.registerBlockState(state, blockId, stateKey);
            }
        }
    }
}
