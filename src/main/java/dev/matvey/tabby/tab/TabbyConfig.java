package dev.matvey.tabby.tab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Stores Tabby's client-side settings in config/tabby.json.
 */
public final class TabbyConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final int DEFAULT_BACKGROUND_TABS = 4;
    private static final int MAX_BACKGROUND_TABS = 16;
    private static final int MAX_RESTORED_ADDRESSES = MAX_BACKGROUND_TABS + 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("tabby.json");

    private static ConfigData data = new ConfigData();

    private TabbyConfig() {
    }

    public static synchronized void load() {
        ConfigData loaded = null;
        if (Files.isRegularFile(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                loaded = GSON.fromJson(reader, ConfigData.class);
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Failed to read Tabby configuration; using defaults", exception);
            }
        }

        data = loaded == null ? new ConfigData() : loaded;
        data.maxBackgroundTabs = Math.max(1, Math.min(MAX_BACKGROUND_TABS, data.maxBackgroundTabs));
        data.keyCreateTab = normalizeKey(data.keyCreateTab, GLFW.GLFW_KEY_T);
        data.keyCloseTab = normalizeKey(data.keyCloseTab, GLFW.GLFW_KEY_W);
        data.keyNextTab = normalizeKey(data.keyNextTab, GLFW.GLFW_KEY_TAB);
        data.keySelectFirstTab = normalizeFirstTabKey(data.keySelectFirstTab);
        data.inGameBarMode = normalizeBarMode(data.inGameBarMode);
        data.restoredServerAddresses = normalizeAddresses(data.restoredServerAddresses);
        save();
    }

    public static synchronized int maxBackgroundTabs() {
        return data.maxBackgroundTabs;
    }

    public static synchronized int keyCreateTab() {
        return data.keyCreateTab;
    }

    public static synchronized int keyCloseTab() {
        return data.keyCloseTab;
    }

    public static synchronized int keyNextTab() {
        return data.keyNextTab;
    }

    public static synchronized int keySelectFirstTab() {
        return data.keySelectFirstTab;
    }

    public static synchronized boolean showInMenus() {
        return data.showInMenus;
    }

    public static synchronized String inGameBarMode() {
        return data.inGameBarMode;
    }

    public static synchronized boolean restoreServerAddresses() {
        return data.restoreServerAddresses;
    }

    public static synchronized List<String> restoredServerAddresses() {
        return List.copyOf(data.restoredServerAddresses);
    }

    public static synchronized void saveRestoredServerAddresses(List<String> addresses) {
        data.restoredServerAddresses = normalizeAddresses(addresses);
        save();
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to write Tabby configuration to {}", CONFIG_PATH, exception);
        }
    }

    private static int normalizeKey(int key, int defaultKey) {
        return key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST ? key : defaultKey;
    }

    private static int normalizeFirstTabKey(int key) {
        if (key < GLFW.GLFW_KEY_SPACE || key > GLFW.GLFW_KEY_LAST - 8) {
            return GLFW.GLFW_KEY_1;
        }
        return key;
    }

    private static String normalizeBarMode(String mode) {
        if (mode == null) {
            return "hover_or_alt";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "always", "hover_or_alt", "hidden" -> normalized;
            default -> "hover_or_alt";
        };
    }

    private static List<String> normalizeAddresses(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return new ArrayList<>();
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String address : addresses) {
            if (address == null) {
                continue;
            }
            String normalized = address.trim();
            if (normalized.isEmpty() || normalized.length() > 255
                || normalized.chars().anyMatch(Character::isWhitespace)) {
                continue;
            }
            unique.add(normalized);
            if (unique.size() >= MAX_RESTORED_ADDRESSES) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    public static final class ConfigData {
        public int maxBackgroundTabs = DEFAULT_BACKGROUND_TABS;
        public int keyCreateTab = GLFW.GLFW_KEY_T;
        public int keyCloseTab = GLFW.GLFW_KEY_W;
        public int keyNextTab = GLFW.GLFW_KEY_TAB;
        public int keySelectFirstTab = GLFW.GLFW_KEY_1;
        public boolean showInMenus = true;
        public String inGameBarMode = "hover_or_alt";
        public boolean restoreServerAddresses = false;
        public List<String> restoredServerAddresses = new ArrayList<>();
    }
}
