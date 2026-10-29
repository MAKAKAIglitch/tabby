package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");

    @Unique
    private IntegratedServer tabby$preservedIntegratedServer;

    @Inject(method = "tick", at = @At("HEAD"))
    private void tabby$captureCurrentContext(CallbackInfo ci) {
        TabManager.captureCurrentState();
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void tabby$storeBackgroundScreen(Screen screen, CallbackInfo ci) {
        if (TabManager.onScreenChangeRequested(screen)) {
            ci.cancel();
            return;
        }
        if (TabManager.interceptBackgroundScreenChange(screen)) {
            ci.cancel();
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void tabby$preserveOtherTabIntegratedServer(Screen screen, boolean keepResourcePacks, CallbackInfo ci) {
        if (this.tabby$preservedIntegratedServer != null || !TabManager.shouldPreserveIntegratedServerDuringDisconnect()) {
            return;
        }

        MinecraftAccessor accessor = (MinecraftAccessor) this;
        IntegratedServer server = accessor.tabby$getSingleplayerServer();
        if (server != null) {
            this.tabby$preservedIntegratedServer = server;
            accessor.tabby$setSingleplayerServer(null);
            LOGGER.debug("Temporarily preserved an integrated server owned by an inactive Tabby tab");
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("RETURN"))
    private void tabby$restoreOtherTabIntegratedServer(Screen screen, boolean keepResourcePacks, CallbackInfo ci) {
        if (this.tabby$preservedIntegratedServer == null) {
            return;
        }

        ((MinecraftAccessor) this).tabby$setSingleplayerServer(this.tabby$preservedIntegratedServer);
        this.tabby$preservedIntegratedServer = null;
        LOGGER.debug("Restored the inactive tab's integrated server after disconnect cleanup");
    }

    @Inject(
        method = "doWorldLoad(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/WorldStem;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void tabby$blockSecondSingleplayerWorld(
        LevelStorageSource.LevelStorageAccess levelAccess,
        PackRepository packRepository,
        WorldStem worldStem,
        boolean newWorld,
        CallbackInfo ci
    ) {
        if (!TabManager.shouldBlockSingleplayerWorldStart()) {
            return;
        }

        TabManager.showSingleplayerWorldBlockedNotice();
        try {
            worldStem.close();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to release a rejected single-player world stem", exception);
        } finally {
            levelAccess.safeClose();
        }
        ci.cancel();
    }

    @Inject(method = "close()V", at = @At("HEAD"))
    private void tabby$shutdownTabContexts(CallbackInfo ci) {
        TabManager.shutdown();
    }
}
