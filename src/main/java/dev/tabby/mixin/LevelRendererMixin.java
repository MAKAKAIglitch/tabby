package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A stale or background tab renderer must never draw a world into the active frame.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void tabby$skipInactiveWorldRender(CallbackInfo ci) {
        if (!TabManager.shouldRenderRenderer((LevelRenderer) (Object) this)) {
            ci.cancel();
        }
    }
}
