package dev.tabby.compat;

import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.components.Button;
import dev.tabby.tab.TabbyConfigScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.List;
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
        List<String> buttonLabels = configScreen.children().stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .map(button -> button.getMessage().getString())
            .toList();
        if (buttonLabels.size() < 10) {
            throw new IllegalStateException("Tabby's Mod Menu settings screen did not initialize all controls");
        }

        for (String requiredLabel : List.of(
            "HUD info plaque:",
            "Tab bar size:",
            "Tab bar color:",
            "Tab bar opacity:"
        )) {
            if (buttonLabels.stream().noneMatch(label -> label.startsWith(requiredLabel))) {
                throw new IllegalStateException("Tabby's Mod Menu settings screen is missing control: " + requiredLabel);
            }
        }

        LOGGER.info("Compatibility test: Mod Menu settings screen, appearance controls, and HUD plaque toggle were initialized");
    }
}
