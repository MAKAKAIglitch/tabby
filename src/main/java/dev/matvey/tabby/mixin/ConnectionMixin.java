package dev.matvey.tabby.mixin;

import dev.matvey.tabby.tab.TabManager;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void tabby$onConnectionCreated(CallbackInfo ci) {
        TabManager.onConnectionCreated((Connection) (Object) this);
    }

    @Inject(method = "setListener", at = @At("HEAD"))
    private void tabby$onSetListener(PacketListener listener, CallbackInfo ci) {
        TabManager.onListenerAssigned((Connection) (Object) this, listener);
    }
}
