package dev.matvey.tabby.tab;

import dev.matvey.tabby.mixin.MinecraftAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Short world-render and context-switch smoke test used with one optional compatibility mod.
 * The test is enabled only by TABBY_COMPAT_SMOKE_TEST=1.
 */
public final class CompatibilitySmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final boolean ENABLED = "1".equals(System.getenv("TABBY_COMPAT_SMOKE_TEST"));
    private static final String MOD_ID = environmentValue("TABBY_COMPAT_MOD", "");
    private static final int JOIN_TIMEOUT_TICKS = 1200;
    private static final int SWITCH_INTERVAL_TICKS = 40;
    private static final int REQUIRED_SWITCH_TICKS = 240;

    private enum Phase {
        CONNECT,
        WAIT_FOR_WORLD,
        SWITCHING
    }

    private static Phase phase = Phase.CONNECT;
    private static int phaseTicks;
    private static int switchTicks;

    private CompatibilitySmokeTest() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void tick(Minecraft client) {
        if (!ENABLED) {
            return;
        }

        switch (phase) {
            case CONNECT -> beginConnection(client);
            case WAIT_FOR_WORLD -> waitForWorld(client);
            case SWITCHING -> exerciseWorldSwitching(client);
        }
    }

    private static void beginConnection(Minecraft client) {
        verifyExpectedModLoaded();
        if (++phaseTicks < 20) {
            return;
        }

        require(TabManager.activeId() == 0, "Compatibility test expected the initial tab to be active");
        String address = environmentValue("TABBY_SERVER_A", "127.0.0.1:25565");
        ServerData serverData = new ServerData("Tabby compatibility server", address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
            client.screen,
            client,
            ServerAddress.parseString(address),
            serverData,
            false,
            null
        );
        LOGGER.info("Compatibility test for {}: connecting to {}", MOD_ID, address);
        phase = Phase.WAIT_FOR_WORLD;
        phaseTicks = 0;
    }

    private static void waitForWorld(Minecraft client) {
        phaseTicks++;
        if (isJoined(context(0))) {
            LOGGER.info("Compatibility test for {}: network world loaded", MOD_ID);
            TabManager.createTab();
            require(TabManager.activeId() == 1, "Creating a second context did not select tab 1");
            require(TabManager.contexts().size() == 2, "Expected exactly two contexts in compatibility test");
            require(context(0).levelRenderer != context(1).levelRenderer, "Tab renderers were unexpectedly shared");
            validateActiveContext(client);
            phase = Phase.SWITCHING;
            switchTicks = 0;
        } else if (phaseTicks > JOIN_TIMEOUT_TICKS) {
            throw new IllegalStateException("Compatibility test timed out waiting for world; mod=" + MOD_ID);
        }
    }

    private static void exerciseWorldSwitching(Minecraft client) {
        switchTicks++;
        validateActiveContext(client);

        if (switchTicks % SWITCH_INTERVAL_TICKS == 0) {
            TabManager.switchToNext();
            validateActiveContext(client);
        }

        if (switchTicks >= REQUIRED_SWITCH_TICKS) {
            require(isJoined(context(0)), "Switching caused the compatibility test's server connection to drop");
            LOGGER.info(
                "Tabby compatibility smoke test passed for {}: world rendering and six context switches completed.",
                MOD_ID
            );
            client.stop();
        }
    }

    private static void validateActiveContext(Minecraft client) {
        TabContext networkTab = context(0);
        TabContext menuTab = context(1);
        require(isJoined(networkTab), "The network tab lost its world, player, listener, or connection");
        require(menuTab != null, "The second tab context disappeared");
        require(networkTab.levelRenderer != menuTab.levelRenderer, "The two tabs share a LevelRenderer");
        require(client.levelRenderer == context(TabManager.activeId()).levelRenderer,
            "Minecraft.levelRenderer does not match the active tab");

        if (TabManager.activeId() == 0) {
            require(client.level == networkTab.level, "Active network level was not restored");
            require(client.player == networkTab.player, "Active network player was not restored");
            require(client.getConnection() == networkTab.clientConnection, "Active network listener was not restored");
            require(client.level != menuTab.level && client.player != menuTab.player,
                "The menu tab retained state from the network tab");
        } else {
            require(client.level == menuTab.level, "Menu tab level state was not restored");
            require(client.player == menuTab.player, "Menu tab player state was not restored");
            require(client.getConnection() == null, "Menu tab inherited the network connection");
        }
    }

    private static void verifyExpectedModLoaded() {
        if (MOD_ID.isBlank()) {
            throw new IllegalStateException("TABBY_COMPAT_MOD must identify the compatibility mod under test");
        }
        require(
            FabricLoader.getInstance().isModLoaded(MOD_ID),
            "The expected compatibility mod '" + MOD_ID + "' was not loaded"
        );
        if ("iris".equals(MOD_ID)) {
            require(FabricLoader.getInstance().isModLoaded("sodium"),
                "Iris compatibility test requires its Sodium dependency to be loaded too");
        }
        LOGGER.info("Compatibility test: verified optional mod '{}' is loaded", MOD_ID);
    }

    private static boolean isJoined(TabContext context) {
        return context != null
            && context.level != null
            && context.player != null
            && context.clientConnection != null
            && context.networkConnection != null
            && context.networkConnection.isConnected();
    }

    private static TabContext context(int id) {
        return TabManager.contexts().stream()
            .filter(tab -> tab.id() == id)
            .findFirst()
            .orElse(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Tabby compatibility smoke test failed (" + MOD_ID + "): " + message);
        }
    }

    private static String environmentValue(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
