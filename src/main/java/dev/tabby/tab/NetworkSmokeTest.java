package dev.tabby.tab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import dev.tabby.mixin.ChatComponentAccessor;
import dev.tabby.mixin.ClientLevelAccessor;
import dev.tabby.mixin.MinecraftAccessor;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
    private static final int CONTENT_TIMEOUT_TICKS = 400;
    private static final String CHAT_A_MARKER = "TABBY_CHAT_A_MARKER";
    private static final String CHAT_B_MARKER = "TABBY_CHAT_B_MARKER";
    private static final String ENTITY_A_MARKER = "TABBY_ENTITY_A_MARKER";
    private static final String ENTITY_B_MARKER = "TABBY_ENTITY_B_MARKER";

    private enum Phase {
        START_SERVER_A,
        WAIT_FOR_SERVER_A,
        WAIT_FOR_SERVER_B,
        WAIT_FOR_CONTENT_ISOLATION,
        SWITCHING,
        START_SINGLEPLAYER,
        WAIT_FOR_SINGLEPLAYER,
        WAIT_FOR_NETWORK_WITH_SINGLEPLAYER,
        WAIT_FOR_SECOND_SINGLEPLAYER_BLOCK,
        COMPLETE
    }

    private static Phase phase = Phase.START_SERVER_A;
    private static int phaseTicks;
    private static int activeTicks;
    private static boolean successLogged;
    private static final int SINGLEPLAYER_TIMEOUT_TICKS = 2400;
    private static final String SINGLEPLAYER_WORLD_A = "tabby-sp-test-a";
    private static final String SINGLEPLAYER_WORLD_B = "tabby-sp-test-b";
    private static int singleplayerTabId = -1;
    private static int networkAfterSingleplayerTabId = -1;
    private static int secondWorldGuardTabId = -1;
    private static int blockedWorldStartCountBefore;
    private static ClientLevel savedSingleplayerLevel;
    private static LocalPlayer savedSingleplayerPlayer;
    private static boolean singleplayerLevelReplacementLogged;
    private static Connection savedSingleplayerConnection;
    private static IntegratedServer savedIntegratedServer;

    private NetworkSmokeTest() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void tick(Minecraft client) {
        if (!ENABLED || !client.isGameLoadFinished()) {
            return;
        }

        switch (phase) {
            case START_SERVER_A -> startServerA(client);
            case WAIT_FOR_SERVER_A -> waitForServerA(client);
            case WAIT_FOR_SERVER_B -> waitForServerB(client);
            case WAIT_FOR_CONTENT_ISOLATION -> waitForContentIsolation(client);
            case SWITCHING -> exerciseConnections(client);
            case START_SINGLEPLAYER -> startSingleplayer(client);
            case WAIT_FOR_SINGLEPLAYER -> waitForSingleplayer(client);
            case WAIT_FOR_NETWORK_WITH_SINGLEPLAYER -> waitForNetworkWithSingleplayer(client);
            case WAIT_FOR_SECOND_SINGLEPLAYER_BLOCK -> waitForSecondSingleplayerBlock(client);
            case COMPLETE -> { }
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
            verifyActiveWorldRecovery(client);

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

            prepareContentIsolation();
            LOGGER.info("Network smoke test: seeded different chat messages, inventory items, and named entities on both servers");
            phase = Phase.WAIT_FOR_CONTENT_ISOLATION;
            phaseTicks = 0;
        } else if (phaseTicks > JOIN_TIMEOUT_TICKS) {
            fail("Tab 1 did not join server B within " + JOIN_TIMEOUT_TICKS + " ticks: "
                + connectionState(client, context(1)));
        }
    }

    private static void prepareContentIsolation() {
        require(isJoined(context(0)) && isJoined(context(1)),
            "Both network tabs must be joined before content-isolation fixtures are prepared");

        runRconCommand("TABBY_RCON_PORT_A", 25575, "give @a minecraft:diamond 1");
        runRconCommand("TABBY_RCON_PORT_B", 25576, "give @a minecraft:emerald 1");
        runRconCommand("TABBY_RCON_PORT_A", 25575,
            "tellraw @a {\"text\":\"" + CHAT_A_MARKER + "\"}");
        runRconCommand("TABBY_RCON_PORT_B", 25576,
            "tellraw @a {\"text\":\"" + CHAT_B_MARKER + "\"}");
        runRconCommand("TABBY_RCON_PORT_A", 25575,
            "execute as @a at @s run summon minecraft:armor_stand ~ ~ ~ {CustomName:'{\"text\":\"" + ENTITY_A_MARKER + "\"}',CustomNameVisible:1b}");
        runRconCommand("TABBY_RCON_PORT_B", 25576,
            "execute as @a at @s run summon minecraft:armor_stand ~ ~ ~ {CustomName:'{\"text\":\"" + ENTITY_B_MARKER + "\"}',CustomNameVisible:1b}");
    }

    private static void waitForContentIsolation(Minecraft client) {
        phaseTicks++;
        validateNoCrossContamination();

        if (contentFixturesReady()) {
            validateContentIsolation();
            LOGGER.info("Network smoke test: chat, inventory, and named-entity state remained isolated between server A and server B");
            phase = Phase.SWITCHING;
            phaseTicks = 0;
            activeTicks = 0;
        } else if (phaseTicks > CONTENT_TIMEOUT_TICKS) {
            fail("Timed out waiting for distinct chat/inventory/entity fixtures: " + contentState());
        }
    }

    private static boolean contentFixturesReady() {
        TabContext serverA = context(0);
        TabContext serverB = context(1);
        return hasItem(serverA, Items.DIAMOND)
            && hasChatMarker(serverA, CHAT_A_MARKER)
            && hasNamedEntity(serverA, ENTITY_A_MARKER)
            && hasItem(serverB, Items.EMERALD)
            && hasChatMarker(serverB, CHAT_B_MARKER)
            && hasNamedEntity(serverB, ENTITY_B_MARKER);
    }

    private static void validateNoCrossContamination() {
        TabContext serverA = contextOrNull(0);
        TabContext serverB = contextOrNull(1);
        if (serverA == null || serverB == null) {
            return;
        }

        require(!hasItem(serverA, Items.EMERALD), "Server B's emerald appeared in server A's inventory");
        require(!hasItem(serverB, Items.DIAMOND), "Server A's diamond appeared in server B's inventory");
        require(!hasChatMarker(serverA, CHAT_B_MARKER), "Server B's chat message appeared in server A's chat history");
        require(!hasChatMarker(serverB, CHAT_A_MARKER), "Server A's chat message appeared in server B's chat history");
        require(!hasNamedEntity(serverA, ENTITY_B_MARKER), "Server B's named entity appeared in server A's world");
        require(!hasNamedEntity(serverB, ENTITY_A_MARKER), "Server A's named entity appeared in server B's world");
    }

    private static void validateContentIsolation() {
        TabContext serverA = context(0);
        TabContext serverB = context(1);
        require(isJoined(serverA) && isJoined(serverB), "Content-isolation validation requires both live server contexts");

        require(hasItem(serverA, Items.DIAMOND), "Server A's inventory did not receive its diamond fixture");
        require(!hasItem(serverA, Items.EMERALD), "Server B's emerald appeared in server A's inventory");
        require(hasItem(serverB, Items.EMERALD), "Server B's inventory did not receive its emerald fixture");
        require(!hasItem(serverB, Items.DIAMOND), "Server A's diamond appeared in server B's inventory");

        require(hasChatMarker(serverA, CHAT_A_MARKER), "Server A's chat history did not receive its marker");
        require(!hasChatMarker(serverA, CHAT_B_MARKER), "Server B's chat message appeared in server A's chat history");
        require(hasChatMarker(serverB, CHAT_B_MARKER), "Server B's chat history did not receive its marker");
        require(!hasChatMarker(serverB, CHAT_A_MARKER), "Server A's chat message appeared in server B's chat history");

        require(hasNamedEntity(serverA, ENTITY_A_MARKER), "Server A's world did not receive its named-entity fixture");
        require(!hasNamedEntity(serverA, ENTITY_B_MARKER), "Server B's named entity appeared in server A's world");
        require(hasNamedEntity(serverB, ENTITY_B_MARKER), "Server B's world did not receive its named-entity fixture");
        require(!hasNamedEntity(serverB, ENTITY_A_MARKER), "Server A's named entity appeared in server B's world");
    }

    private static boolean hasItem(TabContext context, Item item) {
        return context != null
            && context.player != null
            && context.player.getInventory().items.stream().anyMatch(stack -> stack.is(item));
    }

    private static boolean hasChatMarker(TabContext context, String marker) {
        if (context == null || context.gui == null) {
            return false;
        }

        List<GuiMessage> messages = ((ChatComponentAccessor) context.gui.getChat()).tabby$getAllMessages();
        return messages.stream().anyMatch(message -> message.content().getString().contains(marker));
    }

    private static boolean hasNamedEntity(TabContext context, String marker) {
        if (context == null || context.level == null) {
            return false;
        }

        for (Entity entity : context.level.entitiesForRendering()) {
            if (entity.hasCustomName() && entity.getCustomName().getString().contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String contentState() {
        TabContext serverA = contextOrNull(0);
        TabContext serverB = contextOrNull(1);
        return "serverA[diamond=" + hasItem(serverA, Items.DIAMOND)
            + ", emerald=" + hasItem(serverA, Items.EMERALD)
            + ", chatA=" + hasChatMarker(serverA, CHAT_A_MARKER)
            + ", chatB=" + hasChatMarker(serverA, CHAT_B_MARKER)
            + ", entityA=" + hasNamedEntity(serverA, ENTITY_A_MARKER)
            + ", entityB=" + hasNamedEntity(serverA, ENTITY_B_MARKER)
            + "], serverB[diamond=" + hasItem(serverB, Items.DIAMOND)
            + ", emerald=" + hasItem(serverB, Items.EMERALD)
            + ", chatA=" + hasChatMarker(serverB, CHAT_A_MARKER)
            + ", chatB=" + hasChatMarker(serverB, CHAT_B_MARKER)
            + ", entityA=" + hasNamedEntity(serverB, ENTITY_A_MARKER)
            + ", entityB=" + hasNamedEntity(serverB, ENTITY_B_MARKER) + "]";
    }

    private static void verifyTabBarVisibility(Minecraft client) {
        require(TabBar.isVisibleOnScreen(new TitleScreen()),
            "The tab bar must be visible on the main title screen");
        require(TabBar.isVisibleOnScreen(new PauseScreen(true)),
            "The tab bar must be visible in the pause menu");
        require(!TabBar.isVisibleOnScreen(new OptionsScreen(new TitleScreen(), client.options)),
            "The tab bar must be hidden in other menus such as Options");
        require(!TabBar.isVisibleOnScreen(null),
            "The tab bar must be hidden during gameplay when no screen is open");
    }

    private static void verifyActiveWorldRecovery(Minecraft client) {
        TabContext active = context(TabManager.activeId());
        ClientLevel expectedLevel = client.level;
        var expectedPlayer = client.player;
        require(active != null && expectedLevel != null && expectedPlayer != null,
            "Active world recovery test requires a connected player and world");

        // Reproduce the critical state from the Meteor AutoBreed crash report. This is
        // restored synchronously before any subsequent client-tick listeners can observe it.
        client.level = null;
        TabManager.captureCurrentState();

        require(client.level == expectedLevel, "Tabby failed to restore a missing active ClientLevel");
        require(client.player == expectedPlayer, "Tabby failed to restore the active LocalPlayer with its world");
        validateConnections(client);
        LOGGER.info("Network smoke test: active world was restored before the next client tick");
    }

    private static void verifyTabStripInteractions(Minecraft client) {
        verifyTabBarVisibility(client);
        client.setScreen(new PauseScreen(true));

        pressShortcut(client, GLFW.GLFW_KEY_1);
        require(TabManager.activeId() == 0, "Ctrl+1 did not select the first tab");

        pressShortcut(client, GLFW.GLFW_KEY_TAB);
        require(TabManager.activeId() == 1, "Ctrl+Tab did not select the next tab");

        int activeBeforeClick = TabManager.activeId();
        require(
            TabBar.handleClick(client, 10.0, TabBar.stripCenterY(), GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "Clicking the first tab was not handled"
        );
        require(TabManager.activeId() == activeBeforeClick,
            "A tab press switched contexts before mouse release");
        TabBar.handleMouseRelease(client);
        require(TabManager.activeId() == 0, "Releasing a click on the first tab did not activate it");

        int plusX = TabBar.plusButtonCenterX(client);
        require(
            TabBar.handleClick(client, plusX, TabBar.stripCenterY(), GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "Clicking the plus button was not handled (screen="
                + (client.screen == null ? "null" : client.screen.getClass().getSimpleName())
                + ", guiWidth=" + client.getWindow().getGuiScaledWidth()
                + ", x=" + plusX + ", y=" + TabBar.stripCenterY() + ")"
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
        client.setScreen(null);
        verifyStabilityControls(client);
        verifyTabReorderAndPinning(client);
        LOGGER.info("Network smoke test: tab strip, duplicate guard, key reset, tab limit, and single-player guard passed");
    }

    private static void verifyTabReorderAndPinning(Minecraft client) {
        require(TabbyConfig.keyCreateTab() == GLFW.GLFW_KEY_T, "Default Ctrl+T mapping was not loaded");
        require(TabbyConfig.keyCloseTab() == GLFW.GLFW_KEY_W, "Default Ctrl+W mapping was not loaded");
        require(TabbyConfig.keyNextTab() == GLFW.GLFW_KEY_TAB, "Default Ctrl+Tab mapping was not loaded");
        require(TabbyConfig.showInMenus(), "The tab bar should be enabled in menus by default");
        require(TabbyConfig.inGameBarMode().equals("hover_or_alt"), "The default in-game tab bar mode was not loaded");

        int originalActiveId = TabManager.activeId();
        TabManager.switchTo(0);
        client.setScreen(new PauseScreen(true));
        TabManager.captureCurrentState();

        int draggedTabId = 1;
        int targetTabId = 0;
        int sourceX = TabBar.tabCenterX(client, draggedTabId);
        int targetX = TabBar.tabCenterX(client, targetTabId);
        int dragY = TabBar.stripCenterY();
        require(sourceX >= 0 && targetX >= 0, "The tab strip did not expose draggable tab hitboxes");

        require(
            TabBar.handleClick(client, sourceX, dragY, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "Pressing a tab to begin dragging was not handled"
        );
        require(TabManager.activeId() == 0,
            "Pressing a background tab activated it before the drag gesture finished");

        double rawTargetX = (targetX - 10.0) * client.getWindow().getScreenWidth()
            / client.getWindow().getGuiScaledWidth();
        double rawTargetY = dragY * client.getWindow().getScreenHeight()
            / client.getWindow().getGuiScaledHeight();
        TabBar.handleMouseMove(client, rawTargetX, rawTargetY, true);
        require(TabBar.isDragging(), "Moving a held tab did not enter drag mode");
        TabBar.handleMouseRelease(client);

        require(TabManager.contexts().get(0).id() == draggedTabId,
            "Dragging a tab leftward did not move it before the target tab");
        require(TabManager.activeId() == 0,
            "Reordering a tab changed the active tab instead of only changing the order");

        client.setScreen(null);
        TabManager.captureCurrentState();

        TabManager.moveTab(1, TabManager.contexts().size());
        require(TabManager.contexts().get(0).id() == 0 && TabManager.contexts().get(1).id() == 1,
            "Moving the tab back did not restore the original order");

        TabManager.togglePinned(1);
        require(context(1).pinned, "Pinning a tab did not set its pinned state");
        require(TabManager.contexts().get(0).id() == 1, "Pinned tabs were not moved before unpinned tabs");
        TabManager.togglePinned(1);
        require(!context(1).pinned, "Unpinning a tab did not clear its pinned state");
        require(TabManager.contexts().get(0).id() == 0 && TabManager.contexts().get(1).id() == 1,
            "Unpinning a tab did not restore the expected order");

        List<String> previousAddresses = TabbyConfig.restoredServerAddresses();
        TabbyConfig.saveRestoredServerAddresses(List.of(
            "127.0.0.1:25565",
            "127.0.0.1:25566",
            "127.0.0.1:25565",
            " "
        ));
        require(TabbyConfig.restoredServerAddresses().equals(List.of("127.0.0.1:25565", "127.0.0.1:25566")),
            "Stored server addresses were not deduplicated or validated");
        TabbyConfig.saveRestoredServerAddresses(previousAddresses);

        if (TabManager.activeId() != originalActiveId) {
            TabManager.switchTo(originalActiveId);
        }
        LOGGER.info("Network smoke test: real mouse drag, click activation, pinning, and address-only config persistence passed");
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
            validateContentIsolation();
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

            Connection connectionA = context(0).networkConnection;
            require(connectionA != null && connectionA.isConnected(), "Server A was not live before the single-player test");
            TabManager.closeTab(0);
            require(contextOrNull(0) == null, "Closing tab 0 did not remove its context");
            require(!connectionA.isConnected(), "Closing tab 0 did not disconnect server A");
            singleplayerTabId = TabManager.activeId();
            require(contextOrNull(singleplayerTabId) != null, "No active tab remained for the single-player test");

            phase = Phase.START_SINGLEPLAYER;
            phaseTicks = 0;
            LOGGER.info("Network smoke test: two-server isolation passed; opening an integrated single-player world next");
        }
    }

    private static void startSingleplayer(Minecraft client) {
        if (++phaseTicks < 5) {
            return;
        }

        require(TabManager.activeId() == singleplayerTabId, "Single-player fixture must load in its designated tab");
        phase = Phase.WAIT_FOR_SINGLEPLAYER;
        phaseTicks = 0;
        LOGGER.info("Network smoke test: opening single-player save {}", SINGLEPLAYER_WORLD_A);
        client.createWorldOpenFlows().openWorld(SINGLEPLAYER_WORLD_A, () -> { });
    }

    private static void waitForSingleplayer(Minecraft client) {
        phaseTicks++;
        TabContext singleplayer = contextOrNull(singleplayerTabId);
        IntegratedServer server = ((MinecraftAccessor) client).tabby$getSingleplayerServer();

        if (isJoined(singleplayer) && singleplayer.localServer && server != null && server.isRunning()) {
            savedSingleplayerLevel = singleplayer.level;
            savedSingleplayerPlayer = singleplayer.player;
            savedSingleplayerConnection = singleplayer.networkConnection;
            savedIntegratedServer = server;
            require(((MinecraftAccessor) client).tabby$isLocalServer(), "Minecraft did not mark the active fixture as single-player");
            LOGGER.info("Network smoke test: integrated single-player world is live in tab {}", singleplayerTabId);

            TabManager.createTab();
            networkAfterSingleplayerTabId = TabManager.activeId();
            require(networkAfterSingleplayerTabId != singleplayerTabId, "Creating the network tab did not create a separate context");
            phase = Phase.WAIT_FOR_NETWORK_WITH_SINGLEPLAYER;
            phaseTicks = 0;
            String address = environmentAddress("TABBY_SERVER_A", "127.0.0.1:25565");
            LOGGER.info("Network smoke test: connecting a network tab while the single-player world remains in the background");
            connect(client, "Tabby CI network beside single-player", address);
        } else if (phaseTicks > SINGLEPLAYER_TIMEOUT_TICKS) {
            fail("Integrated single-player fixture did not join within " + SINGLEPLAYER_TIMEOUT_TICKS
                + " ticks: " + connectionState(client, singleplayer));
        }
    }

    private static void waitForNetworkWithSingleplayer(Minecraft client) {
        phaseTicks++;
        TabContext network = contextOrNull(networkAfterSingleplayerTabId);
        if (isJoined(network)) {
            verifyNestedPacketContextRestore(client);
            assertSingleplayerWorldRetained(client);
            TabContext singleplayer = contextOrNull(singleplayerTabId);
            require(singleplayer != null, "The single-player tab disappeared while connecting a network tab");
            require(network.level != singleplayer.level, "Network tab reused the single-player ClientLevel");
            require(network.player != singleplayer.player, "Network tab reused the single-player LocalPlayer");
            require(network.networkConnection != savedSingleplayerConnection, "Network tab reused the single-player Connection");

            TabManager.switchTo(singleplayerTabId);
            assertSingleplayerWorldRetained(client);
            TabContext singleplayer = contextOrNull(singleplayerTabId);
            require(singleplayer != null, "The single-player tab disappeared before switch-back");
            require(client.level == singleplayer.level, "Switching to single-player did not restore its current ClientLevel");
            require(client.player == singleplayer.player, "Switching to single-player did not restore its current LocalPlayer");
            require(
                client.getConnection() != null && client.getConnection().getConnection() == savedSingleplayerConnection,
                "Switching to single-player did not restore its packet listener"
            );
            require(((MinecraftAccessor) client).tabby$isLocalServer(), "Single-player mode was lost after switching back");

            TabManager.switchTo(networkAfterSingleplayerTabId);
            assertActiveNetworkTab(client, network);
            assertSingleplayerWorldRetained(client);

            blockedWorldStartCountBefore = TabManager.blockedSingleplayerWorldStartCount();
            TabManager.createTab();
            secondWorldGuardTabId = TabManager.activeId();
            require(secondWorldGuardTabId != singleplayerTabId && secondWorldGuardTabId != networkAfterSingleplayerTabId,
                "Second-world guard test did not create a separate tab");

            phase = Phase.WAIT_FOR_SECOND_SINGLEPLAYER_BLOCK;
            phaseTicks = 0;
            LOGGER.info("Network smoke test: attempting to open a second single-player save while the first is in the background");
            client.createWorldOpenFlows().openWorld(SINGLEPLAYER_WORLD_B, () -> { });
        } else if (phaseTicks > SINGLEPLAYER_TIMEOUT_TICKS) {
            fail("Network tab did not join beside single-player within " + SINGLEPLAYER_TIMEOUT_TICKS
                + " ticks: " + connectionState(client, network));
        }
    }

    private static void waitForSecondSingleplayerBlock(Minecraft client) {
        phaseTicks++;
        int blockedCount = TabManager.blockedSingleplayerWorldStartCount();
        if (blockedCount > blockedWorldStartCountBefore) {
            require(blockedCount == blockedWorldStartCountBefore + 1, "The second-world guard ran an unexpected number of times");
            assertSingleplayerWorldRetained(client);

            TabContext guardTab = contextOrNull(secondWorldGuardTabId);
            require(guardTab != null, "The second-world guard tab disappeared during world loading");
            require(TabManager.activeId() == secondWorldGuardTabId, "World-loading guard unexpectedly changed the active tab");
            require(
                guardTab.level == null,
                "Rejected second-world launch changed the guard tab's ClientLevel: activeTab=" + TabManager.activeId()
                    + ", guardLevel=" + guardTab.level + ", clientLevel=" + client.level
            );
            require(
                client.level == null,
                "Rejected second-world launch changed Minecraft.level: activeTab=" + TabManager.activeId()
                    + ", guardTab=" + guardTab.id() + ", guardLevel=" + guardTab.level + ", clientLevel=" + client.level
            );
            require(guardTab.player == null && client.player == null,
                "Opening a rejected second world leaked a player into the active tab");
            require(client.screen instanceof TitleScreen,
                "Rejected second-world launch left a world-loading screen in the active tab: " + client.screen);
            require(client.levelRenderer == guardTab.levelRenderer,
                "Opening a rejected second world replaced the active tab's world renderer");
            require(client.getConnection() == null,
                "Opening a rejected second world leaked a background connection into the active tab");

            TabContext network = contextOrNull(networkAfterSingleplayerTabId);
            require(isJoined(network), "Attempting a second single-player world disconnected the network tab");
            Connection networkConnection = network.networkConnection;
            TabManager.switchTo(networkAfterSingleplayerTabId);
            assertActiveNetworkTab(client, network);
            assertSingleplayerWorldRetained(client);

            TabManager.closeTab(secondWorldGuardTabId);
            require(contextOrNull(secondWorldGuardTabId) == null, "Closing the guard-test tab did not remove its context");
            require(isJoined(network), "Closing the guard-test tab damaged the network connection");

            TabManager.closeTab(singleplayerTabId);
            require(contextOrNull(singleplayerTabId) == null, "Closing the single-player tab did not remove its context");
            require(savedSingleplayerConnection != null && !savedSingleplayerConnection.isConnected(),
                "Closing the single-player tab did not disconnect its memory connection");
            require(((MinecraftAccessor) client).tabby$getSingleplayerServer() == null,
                "Closing the single-player tab left its IntegratedServer registered");
            require(savedIntegratedServer != null && !savedIntegratedServer.isRunning(),
                "Closing the single-player tab did not stop its IntegratedServer");
            require(isJoined(network) && network.networkConnection == networkConnection,
                "Closing the single-player tab damaged the network tab");

            TabManager.closeTab(networkAfterSingleplayerTabId);
            require(contextOrNull(networkAfterSingleplayerTabId) == null, "Closing the final network tab did not remove its context");
            require(TabManager.contexts().size() == 1, "Final cleanup did not leave exactly one menu context");
            require(client.screen instanceof TitleScreen, "Final cleanup did not return to the title screen");
            LOGGER.info("Tabby single-player integration smoke test passed: the integrated world survived a network-tab connection and a second-world launch was blocked.");
            phase = Phase.COMPLETE;
            client.stop();
        } else if (phaseTicks > SINGLEPLAYER_TIMEOUT_TICKS) {
            fail("The second single-player world was not blocked within " + SINGLEPLAYER_TIMEOUT_TICKS + " ticks");
        }
    }

    /**
     * Reproduces a nested packet task while the outer login handler has a transient,
     * partially initialized gameMode. A background tab switch must restore that exact
     * transient state without publishing it to the persistent network-tab snapshot.
     */
    private static void verifyNestedPacketContextRestore(Minecraft client) {
        TabContext singleplayer = contextOrNull(singleplayerTabId);
        require(singleplayer != null, "Nested-context test has no single-player tab");

        TabManager.runIn(networkAfterSingleplayerTabId, () -> {
            var savedGameMode = client.gameMode;
            var savedLevel = client.level;
            require(savedGameMode != null, "Nested-context test must start with an initialized game mode");

            client.gameMode = null;
            TabManager.runIn(singleplayerTabId, () -> {
                require(
                    client.gameMode == singleplayer.gameMode,
                    "Nested-context test did not install the single-player game mode"
                );
            });

            require(
                client.gameMode == null,
                "Nested background task replaced the outer login handler's transient gameMode"
            );
            require(
                client.level == savedLevel,
                "Nested background task did not restore the outer login handler's transient level"
            );
            client.gameMode = savedGameMode;
        });

        TabContext network = contextOrNull(networkAfterSingleplayerTabId);
        require(network != null && network.gameMode != null,
            "Nested-context test corrupted the persistent network game mode");
        LOGGER.info("Network smoke test: nested packet tasks restore transient login state without corrupting either tab");
    }

    private static void assertSingleplayerWorldRetained(Minecraft client) {
        TabContext singleplayer = contextOrNull(singleplayerTabId);
        require(singleplayer != null, "The single-player TabContext disappeared");
        require(singleplayer.level != null, "The single-player tab lost its ClientLevel");
        require(singleplayer.player != null, "The single-player tab lost its LocalPlayer");
        require(savedSingleplayerLevel != null && savedSingleplayerPlayer != null,
            "The original single-player world/player snapshot is missing");

        // Minecraft may legitimately replace its client-side Level and LocalPlayer objects
        // during a level/respawn transition. World retention is proven by the same integrated
        // server, memory connection, player UUID, and dimension—not by Java object identity.
        require(
            singleplayer.level.dimension().equals(savedSingleplayerLevel.dimension()),
            "The single-player tab changed dimension while a network tab was opened"
        );
        if (singleplayer.level != savedSingleplayerLevel && !singleplayerLevelReplacementLogged) {
            singleplayerLevelReplacementLogged = true;
            LOGGER.info(
                "Network smoke test: Minecraft replaced the single-player ClientLevel instance; "
                    + "same dimension, player identity, memory connection, and IntegratedServer are being verified"
            );
        }

        require(singleplayer.player.getUUID().equals(savedSingleplayerPlayer.getUUID()),
            "The single-player tab switched to a different player identity");

        ClientPacketListener levelListener = ((ClientLevelAccessor) singleplayer.level).tabby$getConnection();
        require(levelListener != null && levelListener.getConnection() == savedSingleplayerConnection,
            "The retained single-player ClientLevel is not owned by the original memory connection");
        require(singleplayer.networkConnection == savedSingleplayerConnection,
            "The single-player tab replaced its memory connection");
        require(singleplayer.networkConnection != null && singleplayer.networkConnection.isConnected(),
            "The single-player memory connection disconnected");
        require(singleplayer.localServer, "The single-player context lost its localServer flag");
        require(((MinecraftAccessor) client).tabby$getSingleplayerServer() == savedIntegratedServer,
            "Minecraft did not retain the original IntegratedServer pointer");
        require(savedIntegratedServer != null && savedIntegratedServer.isRunning(),
            "The original IntegratedServer is no longer running");
    }

    private static void assertActiveNetworkTab(Minecraft client, TabContext network) {
        require(TabManager.activeId() == networkAfterSingleplayerTabId, "The network tab is not active");
        require(client.level == network.level, "Minecraft.level does not match the active network tab");
        require(client.player == network.player, "Minecraft.player does not match the active network tab");
        require(client.getConnection() == network.clientConnection, "Minecraft.getConnection() does not match the active network tab");
        require(!((MinecraftAccessor) client).tabby$isLocalServer(), "A network tab retained the local-server flag");
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
        require(serverA.levelRenderer != serverB.levelRenderer, "Both tabs reference the same LevelRenderer");
        TabMemoryUsage.refresh(TabManager.contexts());
        require(TabMemoryUsage.sample(serverA).estimatedMiB() >= 20, "Server A RAM estimate is missing");
        require(TabMemoryUsage.sample(serverB).estimatedMiB() >= 20, "Server B RAM estimate is missing");

        TabContext active = context(TabManager.activeId());
        require(active != null, "The active tab has no TabContext");
        require(client.level == active.level, "Minecraft.level does not match the active tab");
        require(client.player == active.player, "Minecraft.player does not match the active tab");
        require(client.getConnection() == active.clientConnection, "Minecraft.getConnection() does not match the active tab");
        TabContext inactive = context(TabManager.activeId() == serverA.id() ? serverB.id() : serverA.id());
        require(TabManager.shouldRenderRenderer(active.levelRenderer), "The active tab's renderer was blocked");
        require(!TabManager.shouldRenderRenderer(inactive.levelRenderer), "An inactive tab's renderer was allowed to render");
        require(
            TabMemoryUsage.sample(active).hudLabel(active.title()).contains("RAM ~"),
            "The active tab HUD memory label was not generated"
        );
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

    private static String runRconCommand(String portEnvironmentVariable, int defaultPort, String command) {
        String password = System.getenv("TABBY_RCON_PASSWORD");
        require(password != null && !password.isBlank(), "TABBY_RCON_PASSWORD is required for content-isolation tests");

        try (Socket socket = new Socket()) {
            int port = Integer.parseInt(environmentAddress(portEnvironmentVariable, Integer.toString(defaultPort)));
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            writeRconPacket(output, 1, 3, password);
            RconPacket authentication = readRconPacket(input);
            require(authentication.id() == 1 && authentication.type() == 2,
                "RCON authentication failed for port " + port);

            writeRconPacket(output, 2, 2, command);
            RconPacket response = readRconPacket(input);
            require(response.id() == 2 && response.type() == 0,
                "RCON command did not return a command response on port " + port);

            String normalizedResponse = response.payload().toLowerCase(Locale.ROOT);
            require(!normalizedResponse.contains("unknown command")
                    && !normalizedResponse.startsWith("error executing"),
                "RCON command failed on port " + port + ": " + response.payload());
            return response.payload();
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalStateException(
                "Could not run Tabby content-isolation fixture command on " + portEnvironmentVariable,
                exception
            );
        }
    }

    private static void writeRconPacket(OutputStream output, int requestId, int type, String payload) throws IOException {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int packetLength = payloadBytes.length + 10;
        ByteBuffer packet = ByteBuffer.allocate(packetLength + 4).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(packetLength);
        packet.putInt(requestId);
        packet.putInt(type);
        packet.put(payloadBytes);
        packet.put((byte) 0);
        packet.put((byte) 0);
        output.write(packet.array());
        output.flush();
    }

    private static RconPacket readRconPacket(InputStream input) throws IOException {
        byte[] lengthBytes = readRconBytes(input, 4);
        int packetLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (packetLength < 10 || packetLength > 16384) {
            throw new IOException("Invalid RCON response length: " + packetLength);
        }

        byte[] packetBytes = readRconBytes(input, packetLength);
        ByteBuffer packet = ByteBuffer.wrap(packetBytes).order(ByteOrder.LITTLE_ENDIAN);
        int requestId = packet.getInt();
        int type = packet.getInt();
        int payloadLength = packetLength - 10;
        byte[] payloadBytes = Arrays.copyOfRange(packetBytes, 8, 8 + payloadLength);
        return new RconPacket(requestId, type, new String(payloadBytes, StandardCharsets.UTF_8));
    }

    private static byte[] readRconBytes(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(result, offset, length - offset);
            if (read < 0) {
                throw new IOException("Unexpected end of RCON packet");
            }
            offset += read;
        }
        return result;
    }

    private record RconPacket(int id, int type, String payload) {
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
