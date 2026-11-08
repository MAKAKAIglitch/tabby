package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyboardHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes browser-style Ctrl shortcuts to Tabby.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
    private void tabby$handleTabShortcuts(
        long window,
        int action,
        net.minecraft.client.input.KeyEvent event,
        CallbackInfo ci
    ) {
        Minecraft client = Minecraft.getInstance();
        if (window != client.getWindow().handle() || action != GLFW.GLFW_PRESS) {
            return;
        }

        if (TabManager.handleShortcut(event.key(), event.modifiers())) {
            ci.cancel();
        }
    }
}
