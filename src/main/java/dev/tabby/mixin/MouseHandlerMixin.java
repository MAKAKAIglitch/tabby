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
    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), cancellable = true)
    private void tabby$handleHudTabClick(
        long window,
        int button,
        int action,
        int modifiers,
        CallbackInfo ci
    ) {
        Minecraft client = Minecraft.getInstance();
        if (window != client.getWindow().getWindow()) {
            return;
        }

        if (action == GLFW.GLFW_RELEASE && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            TabBar.handleMouseRelease();
            return;
        }
        if (action != GLFW.GLFW_PRESS || client.screen != null || client.getOverlay() != null
            || (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            return;
        }

        double mouseX = client.mouseHandler.xpos()
            * (double) client.getWindow().getGuiScaledWidth()
            / (double) client.getWindow().getScreenWidth();
        double mouseY = client.mouseHandler.ypos()
            * (double) client.getWindow().getGuiScaledHeight()
            / (double) client.getWindow().getScreenHeight();

        if (TabBar.handleClick(client, mouseX, mouseY, button)) {
            ci.cancel();
        }
    }

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"))
    private void tabby$handleTabDrag(long window, double mouseX, double mouseY, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (window == client.getWindow().getWindow()) {
            TabBar.handleMouseMove(client, mouseX, mouseY);
        }
    }
}
