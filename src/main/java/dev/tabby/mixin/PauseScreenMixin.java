package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Disconnects only the selected Tabby context from the pause menu.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    @Inject(method = "onDisconnect()V", at = @At("HEAD"), cancellable = true)
    private void tabby$disconnectSelectedTab(CallbackInfo ci) {
        if (TabManager.disconnectActiveTabFromPauseScreen()) {
            ci.cancel();
        }
    }
}
