package dev.matvey.tabby.mixin;

import dev.matvey.tabby.tab.TabManager;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "<init>(Lnet/minecraft/network/protocol/PacketFlow;)V", at = @At("RETURN"))
    private void tabby$onConnectionCreated(PacketFlow side, CallbackInfo ci) {
        TabManager.onConnectionCreated((Connection) (Object) this);
    }

    @Inject(
        method = "setupInboundProtocol(Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/PacketListener;)V",
        at = @At("HEAD")
    )
    private void tabby$onSetupInboundProtocol(
        ProtocolInfo<?> protocol,
        PacketListener listener,
        CallbackInfo ci
    ) {
        TabManager.onListenerAssigned((Connection) (Object) this, listener);
    }

    @Inject(method = "handleDisconnection()V", at = @At("RETURN"))
    private void tabby$onDisconnected(CallbackInfo ci) {
        TabManager.onConnectionDisconnected((Connection) (Object) this);
    }
}
