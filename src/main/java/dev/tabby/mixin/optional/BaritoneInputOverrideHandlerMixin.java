package dev.tabby.mixin.optional;

import dev.tabby.tab.TabManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents the primary Baritone instance's forced keys from leaking into
 * movement input while a different Tabby tab is installed.
 */
@Mixin(targets = "baritone.utils.InputOverrideHandler", remap = false)
public abstract class BaritoneInputOverrideHandlerMixin {
    @Inject(method = "isInputForcedDown", at = @At("HEAD"), cancellable = true, require = 0)
    private void tabby$hideForcedInputOutsideMainTab(
        @Coerce Object input,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!TabManager.isBaritoneContextAllowed()) {
            cir.setReturnValue(false);
        }
    }
}
