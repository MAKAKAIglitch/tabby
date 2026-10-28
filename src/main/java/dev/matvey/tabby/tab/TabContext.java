package dev.matvey.tabby.tab;

import dev.matvey.tabby.mixin.MinecraftAccessor;
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
 * Each tab owns its renderer, particle engine, and GUI state. Minecraft still
 * provides shared low-level renderer resources, but world-dependent render state
 * is kept in the tab's LevelRenderer rather than being swapped through one renderer.
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
        context.levelRenderer = new LevelRenderer(
            client,
            client.entityRenderDispatcher,
            client.blockEntityRenderDispatcher,
            client.renderBuffers
        );
        context.particleEngine = new ParticleEngine(null, client.textureManager);
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
        this.screen = client.screen;
        this.pendingConnection = ((MinecraftAccessor) client).tabby$getPendingConnection();

        ClientPacketListener activeConnection = client.getConnection();
        this.clientConnection = activeConnection;
        if (activeConnection != null) {
            this.serverData = activeConnection.getServerData();
            this.networkConnection = activeConnection.getConnection();
            this.packetListener = activeConnection;
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
        ((MinecraftAccessor) client).tabby$setPendingConnection(this.pendingConnection);
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
