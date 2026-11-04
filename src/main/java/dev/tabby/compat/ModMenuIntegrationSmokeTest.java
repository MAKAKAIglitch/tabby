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

    private static List<String> labels(Screen screen) {
        return screen.children().stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .map(button -> button.getMessage().getString())
            .toList();
    }

    private static void requireLabel(List<String> labels, String prefix) {
        if (labels.stream().noneMatch(label -> label.startsWith(prefix))) {
            throw new IllegalStateException("Tabby's settings screen is missing control: " + prefix);
        }
    }

    private static void pressPage(Screen screen, String pageName) {
        Button page = screen.children().stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(button -> button.getMessage().getString().contains(pageName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Tabby's settings page is missing: " + pageName));
        page.onPress();
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
        List<String> buttonLabels = labels(configScreen);
        requireLabel(buttonLabels, "Restore server addresses:");
        requireLabel(buttonLabels, "Show tab bar in menus:");
        requireLabel(buttonLabels, "Background damage alerts:");

        pressPage(configScreen, "Appearance");
        buttonLabels = labels(configScreen);
        requireLabel(buttonLabels, "Tab title:");
        requireLabel(buttonLabels, "Tab bar visibility:");
        requireLabel(buttonLabels, "Show server ping:");
        requireLabel(buttonLabels, "Color theme:");

        pressPage(configScreen, "Performance");
        buttonLabels = labels(configScreen);
        requireLabel(buttonLabels, "Maximum background tabs:");
        requireLabel(buttonLabels, "Diagnostic HUD plaque:");

        LOGGER.info("Compatibility test: all three Mod Menu settings pages and their controls were initialized");
    }
}
