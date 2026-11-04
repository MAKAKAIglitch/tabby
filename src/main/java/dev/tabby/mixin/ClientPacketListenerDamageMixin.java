package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes damage events on the exact play listener that owns each Tabby connection.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerDamageMixin {
    @Inject(
        method = "handleDamageEvent(Lnet/minecraft/network/protocol/game/ClientboundDamageEventPacket;)V",
        at = @At("HEAD")
    )
    private void tabby$notifyDamageOnBackgroundTab(
        ClientboundDamageEventPacket packet,
        CallbackInfo ci
    ) {
        TabManager.onPlayerDamage(
            (ClientPacketListener) (Object) this,
            packet.entityId()
        );
    }

    @Inject(
        method = "handleSetEntityMotion(Lnet/minecraft/network/protocol/game/ClientboundSetEntityMotionPacket;)V",
        at = @At("RETURN")
    )
    private void tabby$rememberBackgroundPlayerVelocity(
        ClientboundSetEntityMotionPacket packet,
        CallbackInfo ci
    ) {
        TabManager.onPlayerVelocity(
            (ClientPacketListener) (Object) this,
            packet.getId()
        );
    }

    @Inject(
        method = "handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V",
        at = @At("RETURN")
    )
    private void tabby$rememberBackgroundExplosionKnockback(
        ClientboundExplodePacket packet,
        CallbackInfo ci
    ) {
        packet.playerKnockback().ifPresent(velocity ->
            TabManager.onPlayerExplosion(
                (ClientPacketListener) (Object) this,
                velocity.x,
                velocity.y,
                velocity.z
            )
        );
    }
}
