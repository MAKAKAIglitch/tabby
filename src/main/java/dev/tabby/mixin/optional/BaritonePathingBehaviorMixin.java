package dev.tabby.mixin.optional;

import dev.tabby.tab.TabManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents Baritone's global pathing state from suppressing controls in another
 * Tabby tab while the original main tab is in the background.
 */
@Pseudo
@Mixin(targets = "baritone.behavior.PathingBehavior", remap = false)
public abstract class BaritonePathingBehaviorMixin {
    @Inject(method = "isPathing", at = @At("HEAD"), cancellable = true, require = 0)
    private void tabby$hidePathingOutsideMainTab(CallbackInfoReturnable<Boolean> cir) {
        if (!TabManager.isBaritoneContextAllowed()) {
            cir.setReturnValue(false);
        }
    }
}
