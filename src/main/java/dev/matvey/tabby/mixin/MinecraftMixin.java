package dev.matvey.tabby.mixin;

import dev.matvey.tabby.tab.TabManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void tabby$captureCurrentContext(CallbackInfo ci) {
        TabManager.captureCurrentState();
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void tabby$storeBackgroundScreen(Screen screen, CallbackInfo ci) {
        if (TabManager.onScreenChangeRequested(screen)) {
            ci.cancel();
            return;
        }
        if (TabManager.interceptBackgroundScreenChange(screen)) {
            ci.cancel();
        }
    }
}
