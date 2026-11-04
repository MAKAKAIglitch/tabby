package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes rendered chat lines while the owning TabContext is installed.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
        at = @At("HEAD")
    )
    private void tabby$countBackgroundMessage(
        Component message,
        MessageSignature signature,
        GuiMessageTag tag,
        CallbackInfo ci
    ) {
        TabManager.onBackgroundChatMessage(message);
    }
}
