package dev.tabby.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.tabby.tab.TabManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Keeps Tabby from switching global client state during the nested ticks performed
 * while a play login is creating its ClientLevel and LocalPlayer.
 *
 * A finally block is essential here: if vanilla throws while processing login, the
 * per-thread login guard must still be removed so future client ticks can recover and
 * the real packet failure is not hidden behind a permanently frozen Tabby guard.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientLoginMixin {
    @WrapMethod(
        method = "handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V"
    )
    private void tabby$wrapClientLogin(ClientboundLoginPacket packet, Operation<Void> original) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean onClientThread = minecraft.isSameThread();

        if (onClientThread) {
            TabManager.beginClientLogin((ClientPacketListener) (Object) this);
        }

        try {
            original.call(packet);
        } finally {
            if (onClientThread) {
                TabManager.endClientLogin((ClientPacketListener) (Object) this);
            }
        }
    }
}
