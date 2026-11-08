package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Unique
    private static final Logger TABBY_LOGGER = LoggerFactory.getLogger("Tabby");

    @Unique
    private boolean tabby$reportedOutOfPhasePacket;

    @Inject(
        method = "doSendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void tabby$guardPlayPacketsDuringConfiguration(
        Packet<?> packet,
        ChannelFutureListener callbacks,
        boolean flush,
        CallbackInfo ci
    ) {
        Connection connection = (Connection) (Object) this;
        PacketListener listener = connection.getPacketListener();
        ConnectionProtocol phase = listener == null ? null : listener.protocol();
        if (!TabManager.shouldSuppressOutOfPhasePlayPacket(packet, phase)) {
            return;
        }

        if (!this.tabby$reportedOutOfPhasePacket) {
            this.tabby$reportedOutOfPhasePacket = true;
            TABBY_LOGGER.warn(
                "Suppressed PLAY-only packet {} while connection phase is {} (listener={}, activeTab={})",
                packet.getClass().getSimpleName(),
                phase == null ? "unknown" : phase.id(),
                listener == null ? "none" : listener.getClass().getSimpleName(),
                TabManager.activeId()
            );
        }
        ci.cancel();
    }

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
