package dev.tabby.tab;

import dev.tabby.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.network.Connection;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.HitResult;

/**
 * Client state owned by one logical tab.
 *
 * World renderers and GUI state are per-tab. Fabric API 0.119.4 maintains a
 * singleton particle factory registry, so this prototype shares Minecraft's
 * ParticleEngine and suppresses particle mutations from background contexts.
 * Particle effects are transient and are not retained independently per tab.
 */
public final class TabContext {
    private final int id;
    private String title;

    public ClientLevel level;
    public LocalPlayer player;
    public MultiPlayerGameMode gameMode;
    public ClientPacketListener clientConnection;
    public ServerData serverData;
    public Connection networkConnection;
    public TickablePacketListener packetListener;
    public Connection pendingConnection;
    public Connection replacingConnection;
    public String playerName;
    public boolean connecting;
    public boolean localServer;
    public boolean pinned;

    public LevelRenderer levelRenderer;
    public ParticleEngine particleEngine;
    public Gui gui;
    public HitResult hitResult;
    public Entity crosshairPickEntity;
    public Entity cameraEntity;
    public Screen screen;

    private TabContext(int id, String title) {
        this.id = id;
        this.title = title;
    }

    public static TabContext capture(Minecraft client, int id, String title) {
        TabContext context = new TabContext(id, title);
        context.captureFrom(client);
        return context;
    }

    public static TabContext createEmpty(Minecraft client, int id) {
        TabContext context = new TabContext(id, "Main Menu");
        context.level = null;
        context.player = null;
        context.gameMode = null;
        context.clientConnection = null;
        context.serverData = null;
        context.networkConnection = null;
        context.packetListener = null;
        context.pendingConnection = null;
        context.replacingConnection = null;
        context.playerName = null;
        context.connecting = false;
        context.localServer = false;
        context.levelRenderer = new LevelRenderer(
            client,
            client.entityRenderDispatcher,
            client.blockEntityRenderDispatcher,
            client.renderBuffers
        );
        context.particleEngine = client.particleEngine;
        context.gui = new Gui(client);
        context.hitResult = null;
        context.crosshairPickEntity = null;
        context.cameraEntity = null;
        context.screen = new TitleScreen();
        return context;
    }

    public void captureFrom(Minecraft client) {
        this.level = client.level;
        this.player = client.player;
        this.gameMode = client.gameMode;
        this.levelRenderer = client.levelRenderer;
        this.particleEngine = client.particleEngine;
        this.gui = client.gui;
        this.hitResult = client.hitResult;
        this.crosshairPickEntity = client.crosshairPickEntity;
        this.cameraEntity = client.cameraEntity;
        if (TabManager.shouldCaptureScreenFromClient()) {
            this.screen = client.screen;
        }
        MinecraftAccessor accessor = (MinecraftAccessor) client;
        this.pendingConnection = accessor.tabby$getPendingConnection();
        this.localServer = accessor.tabby$isLocalServer();

        ClientPacketListener activeConnection = client.getConnection();
        if (activeConnection != null) {
            this.clientConnection = activeConnection;
            this.serverData = activeConnection.getServerData();
            this.networkConnection = activeConnection.getConnection();
            this.packetListener = activeConnection;
        } else if (!this.connecting
            && (this.networkConnection == null || !this.networkConnection.isConnected())) {
            this.clientConnection = null;
            this.networkConnection = null;
            this.packetListener = null;
        }
    }

    public void applyTo(Minecraft client, boolean applyScreen) {
        client.level = this.level;
        client.player = this.player;
        client.gameMode = this.gameMode;
        client.levelRenderer = this.levelRenderer;
        client.particleEngine = this.particleEngine;
        client.gui = this.gui;
        client.hitResult = this.hitResult;
        client.crosshairPickEntity = this.crosshairPickEntity;
        client.cameraEntity = this.cameraEntity;
        MinecraftAccessor accessor = (MinecraftAccessor) client;
        accessor.tabby$setPendingConnection(this.pendingConnection);
        accessor.tabby$setLocalServer(this.localServer);
        if (applyScreen) {
            client.screen = this.screen;
        }
    }

    public int id() {
        return this.id;
    }

    public String title() {
        return this.title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
