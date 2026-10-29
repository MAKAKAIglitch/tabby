package dev.tabby.compat;

import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.tabby.tab.TabbyConfigScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies that Mod Menu can discover and construct Tabby's settings screen.
 */
public final class ModMenuIntegrationSmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");

    private ModMenuIntegrationSmokeTest() {
    }

    public static void verify() {
        EntrypointContainer<ModMenuApi> container = FabricLoader.getInstance()
            .getEntrypointContainers("modmenu", ModMenuApi.class)
            .stream()
            .filter(entry -> "tabby".equals(entry.getProvider().getMetadata().getId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Tabby's Mod Menu entrypoint was not registered"));

        Screen configScreen = container.getEntrypoint()
            .getModConfigScreenFactory()
            .create(new TitleScreen());

        if (!(configScreen instanceof TabbyConfigScreen)) {
            throw new IllegalStateException("Mod Menu did not create Tabby's settings screen");
        }
        configScreen.init(
            Minecraft.getInstance(),
            Minecraft.getInstance().getWindow().getGuiScaledWidth(),
            Minecraft.getInstance().getWindow().getGuiScaledHeight()
        );
        if (configScreen.children().size() < 6) {
            throw new IllegalStateException("Tabby's Mod Menu settings screen did not initialize all controls");
        }

        LOGGER.info("Compatibility test: Mod Menu settings screen and controls were initialized");
    }
}
