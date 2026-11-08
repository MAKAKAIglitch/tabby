package dev.tabby.mixin;

import dev.tabby.tab.TabBar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Allows the tab strip to receive clicks over the in-game HUD, where no Screen exists.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), cancellable = true)
    private void tabby$handleHudTabClick(
        long window,
        net.minecraft.client.input.MouseButtonInfo buttonInfo,
        int action,
        CallbackInfo ci
    ) {
        Minecraft client = Minecraft.getInstance();
        if (window != client.getWindow().handle()) {
            return;
        }

        if (action == GLFW.GLFW_RELEASE && buttonInfo.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            TabBar.handleMouseRelease(client);
            return;
        }
        if (action != GLFW.GLFW_PRESS || client.screen != null || client.getOverlay() != null
            || (buttonInfo.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT && buttonInfo.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            return;
        }

        double mouseX = client.mouseHandler.xpos()
            * (double) client.getWindow().getGuiScaledWidth()
            / (double) client.getWindow().getScreenWidth();
        double mouseY = client.mouseHandler.ypos()
            * (double) client.getWindow().getGuiScaledHeight()
            / (double) client.getWindow().getScreenHeight();

        if (TabBar.handleClick(client, mouseX, mouseY, buttonInfo.button())) {
            ci.cancel();
        }
    }

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"))
    private void tabby$handleTabDrag(long window, double mouseX, double mouseY, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (window == client.getWindow().handle()) {
            TabBar.handleMouseMove(client, mouseX, mouseY);
        }
    }
}
