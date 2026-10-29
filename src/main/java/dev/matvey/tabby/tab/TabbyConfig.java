package dev.matvey.tabby.tab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stores Tabby's small client-side configuration.
 */
public final class TabbyConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final int DEFAULT_BACKGROUND_TABS = 4;
    private static final int MAX_BACKGROUND_TABS = 16;
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
        save();
    }

    public static synchronized int maxBackgroundTabs() {
        return data.maxBackgroundTabs;
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

    public static final class ConfigData {
        public int maxBackgroundTabs = DEFAULT_BACKGROUND_TABS;
    }
}
