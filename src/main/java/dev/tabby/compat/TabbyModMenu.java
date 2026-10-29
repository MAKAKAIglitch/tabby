package dev.tabby.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.tabby.tab.TabbyConfigScreen;

/**
 * Optional Mod Menu integration; Mod Menu is not a required runtime dependency.
 */
public final class TabbyModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TabbyConfigScreen::new;
    }
}
