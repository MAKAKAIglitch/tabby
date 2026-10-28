package dev.matvey.tabby;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point for Tabby.
 */
public final class TabbyClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Tabby initialized for Minecraft 1.21.4.");
    }
}
