package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Tabby from switching global client state during the nested ticks performed
 * while a play login is creating its ClientLevel and LocalPlayer.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientLoginMixin {
    @Inject(
        method = "handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V",
        at = @At("HEAD")
    )
    private void tabby$beginClientLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            TabManager.beginClientLogin((ClientPacketListener) (Object) this);
        }
    }

    @Inject(
        method = "handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V",
        at = @At("RETURN")
    )
    private void tabby$endClientLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            TabManager.endClientLogin((ClientPacketListener) (Object) this);
        }
    }
}
