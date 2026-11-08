package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes the 1.21.11 pause-menu disconnect button through the selected Tabby context.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    @Inject(method = "createPauseMenu()V", at = @At("TAIL"))
    private void tabby$routeDisconnectButtonToSelectedTab(CallbackInfo ci) {
        Button disconnectButton = ((PauseScreenAccessor) this).tabby$getDisconnectButton();
        if (disconnectButton == null) {
            return;
        }

        ButtonAccessor accessor = (ButtonAccessor) (Object) disconnectButton;
        Button.OnPress vanillaAction = accessor.tabby$getOnPress();
        accessor.tabby$setOnPress(button -> {
            if (!TabManager.disconnectActiveTabFromPauseScreen()) {
                vanillaAction.onPress(button);
            }
        });
    }
}
