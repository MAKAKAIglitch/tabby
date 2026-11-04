package dev.tabby.tab;

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
    private static final int DEFAULT_BACKGROUND_TABS = 3;
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
        // Preserve the default-on behaviour when older JSON files do not contain this setting.
        if (data.notifyBackgroundDamage == null) data.notifyBackgroundDamage = Boolean.TRUE;
        if (data.showDamageFlash == null) data.showDamageFlash = Boolean.TRUE;
        if (data.showTabVitals == null) data.showTabVitals = Boolean.TRUE;
        data.tabTitleMode = normalizeTabTitleMode(data.tabTitleMode);
        data.barVisibilityMode = normalizeBarVisibilityMode(data.barVisibilityMode);
        data.maxBackgroundTabs = Math.max(1, Math.min(MAX_BACKGROUND_TABS, data.maxBackgroundTabs));
        data.keyCreateTab = normalizeKey(data.keyCreateTab, GLFW.GLFW_KEY_T);
        data.keyCloseTab = normalizeKey(data.keyCloseTab, GLFW.GLFW_KEY_W);
        data.keyNextTab = normalizeKey(data.keyNextTab, GLFW.GLFW_KEY_TAB);
        data.keySelectFirstTab = normalizeFirstTabKey(data.keySelectFirstTab);
        data.inGameBarMode = normalizeBarMode(data.inGameBarMode);
        data.barScalePercent = normalizeBarScale(data.barScalePercent);
        data.barColorPreset = normalizeBarColor(data.barColorPreset);
        data.barOpacityPercent = normalizeBarOpacity(data.barOpacityPercent);
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

    public static synchronized String tabTitleMode() {
        return data.tabTitleMode;
    }

    public static synchronized String barVisibilityMode() {
        return data.barVisibilityMode;
    }

    public static synchronized boolean showPing() {
        return data.showPing;
    }

    public static synchronized boolean showTabMemoryLabel() {
        return data.showTabMemoryLabel;
    }

    public static synchronized String inGameBarMode() {
        return data.inGameBarMode;
    }

    public static synchronized boolean restoreServerAddresses() {
        return data.restoreServerAddresses;
    }

    public static synchronized boolean showHudInfo() {
        return data.showHudInfo;
    }

    public static synchronized boolean notifyBackgroundDamage() {
        return Boolean.TRUE.equals(data.notifyBackgroundDamage);
    }

    public static synchronized boolean showDamageFlash() {
        return Boolean.TRUE.equals(data.showDamageFlash);
    }

    public static synchronized boolean showTabVitals() {
        return Boolean.TRUE.equals(data.showTabVitals);
    }

    public static synchronized int barScalePercent() {
        return data.barScalePercent;
    }

    public static synchronized String barColorPreset() {
        return data.barColorPreset;
    }

    public static synchronized int barOpacityPercent() {
        return data.barOpacityPercent;
    }

    public static synchronized void setShowInMenus(boolean enabled) {
        data.showInMenus = enabled;
        save();
    }

    public static synchronized void setTabTitleMode(String mode) {
        data.tabTitleMode = normalizeTabTitleMode(mode);
        save();
    }

    public static synchronized void setBarVisibilityMode(String mode) {
        data.barVisibilityMode = normalizeBarVisibilityMode(mode);
        save();
    }

    public static synchronized void setShowPing(boolean enabled) {
        data.showPing = enabled;
        save();
    }

    public static synchronized void setShowTabMemoryLabel(boolean enabled) {
        data.showTabMemoryLabel = enabled;
        save();
    }

    public static synchronized void setShowHudInfo(boolean enabled) {
        data.showHudInfo = enabled;
        save();
    }

    public static synchronized void setNotifyBackgroundDamage(boolean enabled) {
        data.notifyBackgroundDamage = enabled;
        save();
    }

    public static synchronized void setShowDamageFlash(boolean enabled) {
        data.showDamageFlash = enabled;
        save();
    }

    public static synchronized void setShowTabVitals(boolean enabled) {
        data.showTabVitals = enabled;
        save();
    }

    public static synchronized void setBarScalePercent(int percent) {
        data.barScalePercent = normalizeBarScale(percent);
        save();
    }

    public static synchronized void setBarColorPreset(String colorPreset) {
        data.barColorPreset = normalizeBarColor(colorPreset);
        save();
    }

    public static synchronized void setBarOpacityPercent(int percent) {
        data.barOpacityPercent = normalizeBarOpacity(percent);
        save();
    }

    public static synchronized void setMaxBackgroundTabs(int maxBackgroundTabs) {
        data.maxBackgroundTabs = Math.max(1, Math.min(MAX_BACKGROUND_TABS, maxBackgroundTabs));
        save();
    }

    public static synchronized void setKeyCreateTab(int key) {
        data.keyCreateTab = normalizeKey(key, GLFW.GLFW_KEY_T);
        save();
    }

    public static synchronized void setKeyCloseTab(int key) {
        data.keyCloseTab = normalizeKey(key, GLFW.GLFW_KEY_W);
        save();
    }

    public static synchronized void setKeyNextTab(int key) {
        data.keyNextTab = normalizeKey(key, GLFW.GLFW_KEY_TAB);
        save();
    }

    public static synchronized void setRestoreServerAddresses(boolean enabled) {
        data.restoreServerAddresses = enabled;
        save();
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

    private static int normalizeBarScale(int percent) {
        return switch (percent) {
            case 75, 85, 100, 115, 125 -> percent;
            default -> 100;
        };
    }

    private static int normalizeBarOpacity(int percent) {
        if (percent < 20 || percent > 100) {
            return 90;
        }
        return Math.max(20, Math.min(100, Math.round(percent / 10.0f) * 10));
    }

    private static String normalizeBarColor(String colorPreset) {
        if (colorPreset == null) {
            return "blue";
        }
        String normalized = colorPreset.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "blue", "purple", "green", "orange", "graphite", "cyan", "rose" -> normalized;
            default -> "blue";
        };
    }

    private static String normalizeTabTitleMode(String mode) {
        if (mode == null) return "address";
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "address", "server_name" -> normalized;
            default -> "address";
        };
    }

    private static String normalizeBarVisibilityMode(String mode) {
        if (mode == null) return "always";
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "always", "hover_or_alt", "hidden" -> normalized;
            default -> "always";
        };
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
        public String tabTitleMode = "address";
        public String barVisibilityMode = "always";
        public boolean showPing = true;
        public boolean showTabMemoryLabel = false;
        public String inGameBarMode = "hover_or_alt";
        public boolean showHudInfo = false;
        public Boolean notifyBackgroundDamage = Boolean.TRUE;
        public Boolean showDamageFlash = Boolean.TRUE;
        public Boolean showTabVitals = Boolean.TRUE;
        public int barScalePercent = 100;
        public String barColorPreset = "blue";
        public int barOpacityPercent = 90;
        public boolean restoreServerAddresses = false;
        public List<String> restoredServerAddresses = new ArrayList<>();
    }
}
