package dev.matvey.tabby.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("connection")
    ClientPacketListener tabby$getClientConnection();

    @Accessor("connection")
    void tabby$setClientConnection(ClientPacketListener connection);

    @Accessor("pendingConnection")
    Connection tabby$getPendingConnection();

    @Accessor("pendingConnection")
    void tabby$setPendingConnection(Connection connection);
}
