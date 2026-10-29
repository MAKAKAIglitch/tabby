package dev.tabby.mixin;

import dev.tabby.tab.TabBar;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Displays the selected tab's estimated retained-world memory on the in-game HUD.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
    private void tabby$renderActiveTabMemory(
        GuiGraphics graphics,
        DeltaTracker tickCounter,
        CallbackInfo ci
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null && client.level != null && client.getOverlay() == null) {
            TabBar.renderHudMemory(client, graphics);
        }
    }
}
