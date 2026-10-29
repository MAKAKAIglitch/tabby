package dev.matvey.tabby.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("pendingConnection")
    Connection tabby$getPendingConnection();

    @Accessor("pendingConnection")
    void tabby$setPendingConnection(Connection connection);

    @Accessor("isLocalServer")
    boolean tabby$isLocalServer();

    @Accessor("isLocalServer")
    void tabby$setLocalServer(boolean localServer);

    @Accessor("singleplayerServer")
    IntegratedServer tabby$getSingleplayerServer();

    @Accessor("singleplayerServer")
    void tabby$setSingleplayerServer(IntegratedServer server);
}
