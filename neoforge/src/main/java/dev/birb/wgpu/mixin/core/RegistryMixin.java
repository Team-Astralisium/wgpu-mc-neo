package dev.birb.wgpu.mixin.core;

import dev.birb.wgpu.rust.WgpuNative;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.stream.Collectors;

@Mixin(Registry.class)
public interface RegistryMixin {
    @Inject(method = "register(Lnet/minecraft/core/Registry;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)Ljava/lang/Object;", at = @At("RETURN"))
    private static void registryHook(Registry<?> registry, ResourceLocation id, Object entry, CallbackInfoReturnable<Object> cir) {
        if (entry instanceof Block block) {
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();

            WgpuNative.registerBlock(blockId);

            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                String stateKey = state.getValues().entrySet().stream()
                        .map(RegistryMixin::propertyEntryToString)
                        .collect(Collectors.joining(","));
                WgpuNative.registerBlockState(state, blockId, stateKey);
            }
        }
    }

    private static String propertyEntryToString(Map.Entry<Property<?>, Comparable<?>> entry) {
        return entry.getKey().getName() + "=" + propertyValueName(entry.getKey(), entry.getValue());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(Property property, Comparable value) {
        return property.getName(value);
    }
}
