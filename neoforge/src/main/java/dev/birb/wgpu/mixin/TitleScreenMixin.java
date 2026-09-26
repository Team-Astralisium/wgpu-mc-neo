package dev.birb.wgpu.mixin;

import dev.birb.wgpu.BlockCache;
import dev.birb.wgpu.WgpuMcMod;
import dev.birb.wgpu.entity.EntityModelUpload;
import dev.birb.wgpu.render.Wgpu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two things that have to happen once the loader's progress has reached the main screen.
 *
 * <p>The first is the flag itself: {@link Wgpu#setInitialized} is what the resource-reload listener
 * and the entity upload below wait for, and the 1.21.1 port set it here - from the main screen, which
 * is the first moment the renderer, the resource manager and the block atlas are all in place at
 * once. Nothing ever set it in this revision, so both of those paths had been dead: the reload
 * listener returned before rebuilding anything, and no entity model was ever uploaded.
 *
 * <p>{@code init} rather than a render hook, because the screen is set up on every launch - a
 * `--quickPlaySingleplayer` run sets this screen and then replaces it with the world load, so a hook
 * that only runs when the screen is *drawn* would never fire there.
 *
 * <p>The second is the block cache, which is asked for once the same conditions hold; see
 * {@link BlockCache} for why it waits a few seconds longer than this.
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {
    @Unique
    private boolean wgpu_mc$updatedTitle = false;

    @Inject(method = "init", at = @At("HEAD"))
    private void wgpuMc$markInitialised(CallbackInfo ci) {
        Wgpu.setInitialized(true);
    }

    // 26.1 renamed Screen#render to Screen#extractRenderState and GuiGraphics to GuiGraphicsExtractor.
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Wgpu.probeNativeBackendOnce();

        if (!wgpu_mc$updatedTitle && Wgpu.isInitialized()) {
            BlockCache.start();

            Minecraft.getInstance().updateTitle();
            wgpu_mc$updatedTitle = true;

            try {
                EntityModelUpload.uploadEntityModels();
                WgpuMcMod.MAY_INJECT_PART_IDS = true;
                WgpuMcMod.ENTITIES_UPLOADED = true;
            } catch (Throwable throwable) {
                WgpuMcMod.LOGGER.error("Failed to upload entity model definitions", throwable);
            }
        }
    }
}
