package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy adds voxy$createRenderer() to LevelRenderer through its own mixin.
 * Prevent that GPU-heavy renderer from being created for a background tab.
 * TabManager recreates it when the tab becomes active.
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class VoxyLevelRendererMixin {
    @Dynamic("Added to LevelRenderer by Voxy's MixinLevelRenderer")
    @Inject(
        method = "voxy$createRenderer()V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void tabby$deferBackgroundVoxyRenderer(CallbackInfo ci) {
        if (TabManager.shouldDeferVoxyRendererCreation((LevelRenderer) (Object) this)) {
            ci.cancel();
        }
    }
}
