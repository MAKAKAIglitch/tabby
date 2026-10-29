package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Prevents vanilla's eager disconnect from breaking a tab when a duplicate server is opened.
 */
@Mixin(net.minecraft.client.gui.screens.ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(
        method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void tabby$blockDuplicateServer(
        Screen parent,
        Minecraft minecraft,
        ServerAddress address,
        ServerData serverData,
        boolean quickPlay,
        TransferState transferState,
        CallbackInfo ci
    ) {
        if (TabManager.handleConnectionAttempt(address, serverData)) {
            ci.cancel();
        }
    }
}
