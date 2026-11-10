package dev.tabby.mixin.optional;

import dev.tabby.tab.TabManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Meteor's singleton command-GUI handler belongs to the visible client tab.
 * Background PRE/POST events still reach tab-owned modules and Systems, but must
 * not consume the selected tab's global keyboard click or open a hidden GUI.
 */
@Pseudo
@Mixin(targets = "meteordevelopment.meteorclient.MeteorClient", remap = false)
public abstract class MeteorClientTickMixin {
    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void tabby$skipGlobalMeteorCommandHandlerOnBackgroundTick(CallbackInfo ci) {
        if (TabManager.isBackgroundContext()) {
            ci.cancel();
        }
    }
}
