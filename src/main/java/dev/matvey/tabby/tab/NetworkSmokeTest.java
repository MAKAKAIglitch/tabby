package dev.matvey.tabby.tab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import dev.matvey.tabby.mixin.MinecraftAccessor;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

/**
 * Optional end-to-end test used by CI with two local offline-mode servers.
 * It is enabled only when TABBY_NETWORK_SMOKE_TEST=1 is present.
 */
public final class NetworkSmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final boolean ENABLED = "1".equals(System.getenv("TABBY_NETWORK_SMOKE_TEST"));
    private static final int JOIN_TIMEOUT_TICKS = 1200;
    private static final int REQUIRED_SWITCH_TICKS = 2400;
    private static final int SWITCH_INTERVAL_TICKS = 40;

    private enum Phase {
        START_SERVER_A,
        WAIT_FOR_SERVER_A,
        WAIT_FOR_SERVER_B,
        SWITCHING
    }

    private static Phase phase = Phase.START_SERVER_A;
    private static int phaseTicks;
    private static int activeTicks;
    private static boolean successLogged;

    private NetworkSmokeTest() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void tick(Minecraft client) {
        if (!ENABLED) {
            return;
        }

        switch (phase) {
            case START_SERVER_A -> startServerA(client);
            case WAIT_FOR_SERVER_A -> waitForServerA(client);
            case WAIT_FOR_SERVER_B -> waitForServerB(client);
            case SWITCHING -> exerciseConnections(client);
        }
    }

    private static void startServerA(Minecraft client) {
        if (++phaseTicks < 20) {
            return;
        }

        require(TabManager.activeId() == 0, "Expected the first tab to be active before connecting to server A");
        String address = environmentAddress("TABBY_SERVER_A", "127.0.0.1:25565");
        connect(client, "Tabby CI server A", address);
        LOGGER.info("Network smoke test: connecting tab 0 to {}", address);
        phase = Phase.WAIT_FOR_SERVER_A;
        phaseTicks = 0;
    }

    private static void waitForServerA(Minecraft client) {
        phaseTicks++;
        TabContext serverA = context(0);
        if (isJoined(serverA)) {
            LOGGER.info("Network smoke test: tab 0 joined server A");
            TabManager.createTestTab();
            require(TabManager.activeId() == 1, "Creating the second tab did not select tab 1");

            String address = environmentAddress("TABBY_SERVER_B", "127.0.0.1:25566");
            connect(client, "Tabby CI server B", address);
            LOGGER.info("Network smoke test: connecting tab 1 to {}", address);
            phase = Phase.WAIT_FOR_SERVER_B;
            phaseTicks = 0;
        } else if (phaseTicks > JOIN_TIMEOUT_TICKS) {
            fail("Tab 0 did not join server A within " + JOIN_TIMEOUT_TICKS + " ticks: "
                + connectionState(client, context(0)));
        }
    }

    private static void waitForServerB(Minecraft client) {
        phaseTicks++;
        if (isJoined(context(1))) {
            validateConnections(client);

            TabManager.switchTo(0);
            Screen activeScreen = client.screen;
            TabContext background = context(1);
            Screen previousBackgroundScreen = background.screen;
            Screen replacementScreen = new TitleScreen();
            TabManager.runIn(1, () -> client.setScreen(replacementScreen));
            require(background.screen == replacementScreen, "Background screen state was not retained");
            require(client.screen == activeScreen, "Background screen change replaced the active screen");
            background.screen = previousBackgroundScreen;
            TabManager.switchTo(1);
            validateConnections(client);
            verifyTabStripInteractions(client);

            LOGGER.info("Network smoke test: tab 1 joined server B; starting connection-isolation test");
            phase = Phase.SWITCHING;
            phaseTicks = 0;
        } else if (phaseTicks > JOIN_TIMEOUT_TICKS) {
            fail("Tab 1 did not join server B within " + JOIN_TIMEOUT_TICKS + " ticks: "
                + connectionState(client, context(1)));
        }
    }

    private static void verifyTabStripInteractions(Minecraft client) {
        pressShortcut(client, GLFW.GLFW_KEY_1);
        require(TabManager.activeId() == 0, "Ctrl+1 did not select the first tab");

        pressShortcut(client, GLFW.GLFW_KEY_TAB);
        require(TabManager.activeId() == 1, "Ctrl+Tab did not select the next tab");

        require(
            TabBar.handleClick(client, 10.0, TabBar.stripCenterY(), GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "Clicking the first tab was not handled"
        );
        require(TabManager.activeId() == 0, "Clicking the first tab did not activate it");

        int plusX = TabBar.plusButtonCenterX(client);
        require(
            TabBar.handleClick(client, plusX, TabBar.stripCenterY(), GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "Clicking the plus button was not handled"
        );
        require(TabManager.contexts().size() == 3, "The plus button did not create a third tab");
        int temporaryTabId = TabManager.activeId();
        require(temporaryTabId == 2, "The plus button did not activate the newly created tab");

        int closeX = TabBar.closeButtonCenterX(client, temporaryTabId);
        require(closeX >= 0, "The new tab has no close button");
        require(
            TabBar.handleClick(client, closeX, TabBar.stripCenterY(), GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "Clicking the close button was not handled"
        );
        require(TabManager.contexts().size() == 2, "The close button did not remove the third tab");
        require(TabManager.activeId() == 0, "Closing the active third tab did not select the first remaining tab");

        pressShortcut(client, GLFW.GLFW_KEY_T);
        require(TabManager.contexts().size() == 3, "Ctrl+T did not create a third tab");
        int keyboardCreatedTabId = TabManager.activeId();
        require(
            keyboardCreatedTabId != 0 && keyboardCreatedTabId != 1,
            "Ctrl+T did not activate the newly created tab"
        );
        pressShortcut(client, GLFW.GLFW_KEY_W);
        require(TabManager.contexts().size() == 2, "Ctrl+W did not close the active tab");
        require(TabManager.activeId() == 0, "Ctrl+W did not select a remaining tab");

        pressShortcut(client, GLFW.GLFW_KEY_TAB);
        require(TabManager.activeId() == 1, "Ctrl+Tab did not restore the second tab");
        validateConnections(client);
        verifyDuplicateConnectionGuard(client);
        verifyStabilityControls(client);
        LOGGER.info("Network smoke test: tab strip, duplicate guard, key reset, tab limit, and single-player guard passed");
    }

    private static void verifyStabilityControls(Minecraft client) {
        KeyMapping heldKey = new KeyMapping(
            "key.tabby.smoke_held_key",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F11,
            "category.tabby"
        );
        heldKey.setDown(true);
        TabManager.switchTo(0);
        require(!heldKey.isDown(), "Switching tabs did not release held key mappings");
        TabManager.switchTo(1);
        validateConnections(client);

        TabContext first = context(0);
        boolean previousLocalFlag = first.localServer;
        first.localServer = true;
        try {
            require(TabManager.shouldBlockSingleplayerWorldStart(), "A second single-player world was not blocked when one exists in another tab");
        } finally {
            first.localServer = previousLocalFlag;
        }

        int maxBackgroundTabs = TabbyConfig.maxBackgroundTabs();
        while (TabManager.contexts().size() - 1 < maxBackgroundTabs) {
            TabManager.createTab();
        }
        int atLimit = TabManager.contexts().size();
        require(atLimit == maxBackgroundTabs + 1, "Configured background-tab limit produced the wrong total tab count");
        TabManager.createTab();
        require(TabManager.contexts().size() == atLimit, "The configured background-tab limit was not enforced");

        for (TabContext context : List.copyOf(TabManager.contexts())) {
            if (context.id() != 0 && context.id() != 1) {
                TabManager.closeTab(context.id());
            }
        }
        TabManager.switchTo(1);
        validateConnections(client);
        LOGGER.info("Network smoke test: key release, background-tab limit, and single-player guard passed");
    }

    private static void verifyDuplicateConnectionGuard(Minecraft client) {
        require(TabManager.activeId() == 1, "Duplicate-connection test must begin on tab 1");
        ClientLevel activeLevel = client.level;
        var activePlayer = client.player;
        ClientPacketListener activeListener = client.getConnection();
        int tabCount = TabManager.contexts().size();

        ConnectScreen.startConnecting(
            client.screen,
            client,
            ServerAddress.parseString("127.0.0.1:25565"),
            new ServerData("Duplicate server A", "127.0.0.1:25565", ServerData.Type.OTHER),
            false,
            null
        );

        require(client.level == activeLevel, "Duplicate attempt disconnected or replaced the active world");
        require(client.player == activePlayer, "Duplicate attempt replaced the active player");
        require(client.getConnection() == activeListener, "Duplicate attempt replaced the active listener");
        require(TabManager.contexts().size() == tabCount, "Duplicate attempt created another tab");
        validateConnections(client);
        LOGGER.info("Network smoke test: duplicate server connection was blocked without disconnecting either tab");
    }

    private static void pressShortcut(Minecraft client, int key) {
        client.keyboardHandler.keyPress(
            client.getWindow().getWindow(),
            key,
            0,
            GLFW.GLFW_PRESS,
            GLFW.GLFW_MOD_CONTROL
        );
    }

    private static void exerciseConnections(Minecraft client) {
        activeTicks++;
        validateConnections(client);

        if (activeTicks % SWITCH_INTERVAL_TICKS == 0) {
            TabManager.switchToNext();
            validateConnections(client);
        }

        if (activeTicks % 200 == 0) {
            LOGGER.info(
                "Network smoke test: {} ticks elapsed; both connections live; active tab {}",
                activeTicks,
                TabManager.activeId()
            );
        }

        if (!successLogged && activeTicks >= REQUIRED_SWITCH_TICKS) {
            successLogged = true;
            LOGGER.info(
                "Tabby network smoke test passed: both connections remained live for {} ticks.",
                activeTicks
            );

            Connection connectionB = context(1).networkConnection;
            require(connectionB != null && connectionB.isConnected(), "Server B was not live before close cleanup");
            TabManager.closeTab(1);
            require(contextOrNull(1) == null, "Closing tab 1 did not remove its context");
            require(!connectionB.isConnected(), "Closing tab 1 did not disconnect its network connection");
            TabContext remainingTab = context(0);
            require(isJoined(remainingTab), "Closing tab 1 damaged the connection in tab 0");
            require(client.level == remainingTab.level, "Closing tab 1 changed the remaining active world");
            require(client.player == remainingTab.player, "Closing tab 1 changed the remaining active player");
            require(client.getConnection() == remainingTab.clientConnection, "Closing tab 1 changed the remaining active connection");

            Connection connectionA = context(0).networkConnection;
            require(connectionA != null && connectionA.isConnected(), "Server A was not live before final-tab cleanup");
            TabManager.closeTab(0);
            require(!connectionA.isConnected(), "Closing the last tab did not disconnect server A");
            require(TabManager.contexts().size() == 1, "Closing the last tab did not create one menu context");
            require(client.screen instanceof TitleScreen, "Closing the last tab did not return to the title screen");
            LOGGER.info("Network smoke test: per-tab disconnect cleanup and final-tab return passed.");
            client.stop();
        }
    }

    private static void connect(Minecraft client, String label, String address) {
        ServerData serverData = new ServerData(label, address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
            client.screen,
            client,
            ServerAddress.parseString(address),
            serverData,
            false,
            null
        );
    }

    private static void validateConnections(Minecraft client) {
        TabContext serverA = context(0);
        TabContext serverB = context(1);
        require(isJoined(serverA), "Server A disconnected or lost its client world/player");
        require(isJoined(serverB), "Server B disconnected or lost its client world/player");
        require(serverA.level != serverB.level, "Both tabs reference the same ClientLevel instance");
        require(serverA.player != serverB.player, "Both tabs reference the same LocalPlayer instance");
        require(serverA.networkConnection != serverB.networkConnection, "Both tabs reference the same network connection");

        TabContext active = context(TabManager.activeId());
        require(active != null, "The active tab has no TabContext");
        require(client.level == active.level, "Minecraft.level does not match the active tab");
        require(client.player == active.player, "Minecraft.player does not match the active tab");
        require(client.getConnection() == active.clientConnection, "Minecraft.getConnection() does not match the active tab");
    }

    private static String connectionState(Minecraft client, TabContext context) {
        Connection pending = ((MinecraftAccessor) client).tabby$getPendingConnection();
        PacketListener pendingListener = pending == null ? null : pending.getPacketListener();
        Connection ownedConnection = context == null ? null : context.networkConnection;
        PacketListener ownedListener = ownedConnection == null ? null : ownedConnection.getPacketListener();
        return "activeTab=" + TabManager.activeId()
            + ", clientLevel=" + (client.level != null)
            + ", clientPlayer=" + (client.player != null)
            + ", clientConnection=" + (client.getConnection() == null ? "null" : client.getConnection().getClass().getSimpleName())
            + ", pendingConnection=" + (pending == null ? "null" : "connected=" + pending.isConnected())
            + ", pendingListener=" + (pendingListener == null ? "null" : pendingListener.getClass().getSimpleName())
            + ", tabLevel=" + (context != null && context.level != null)
            + ", tabPlayer=" + (context != null && context.player != null)
            + ", tabConnection=" + (ownedConnection == null ? "null" : "connected=" + ownedConnection.isConnected())
            + ", tabListener=" + (ownedListener == null ? "null" : ownedListener.getClass().getSimpleName())
            + ", tabListenerOwner=" + (ownedListener instanceof TabOwnedListener owned ? owned.tabby$getTabId() : "unowned")
            + ", clientPendingMatchesTab=" + (context != null && pending == context.pendingConnection);
    }

    private static boolean isJoined(TabContext context) {
        return context != null
            && context.level != null
            && context.player != null
            && context.clientConnection != null
            && context.networkConnection != null
            && context.networkConnection.isConnected();
    }

    private static TabContext contextOrNull(int id) {
        return TabManager.contexts().stream()
            .filter(tab -> tab.id() == id)
            .findFirst()
            .orElse(null);
    }

    private static TabContext context(int id) {
        return TabManager.contexts().stream()
            .filter(tab -> tab.id() == id)
            .findFirst()
            .orElse(null);
    }

    private static String environmentAddress(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    private static void fail(String message) {
        LOGGER.error("Network smoke test failed: {}", message);
        throw new IllegalStateException("Tabby network smoke test failed: " + message);
    }
}
