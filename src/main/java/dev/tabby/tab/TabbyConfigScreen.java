package dev.tabby.tab;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** Native, paged Tabby settings screen. Changes are saved immediately. */
public final class TabbyConfigScreen extends Screen {
    private static final int[] LIMITS = {1, 2, 3, 4, 6, 8, 12, 16};
    private static final int[] SIZES = {75, 85, 100, 115, 125};
    private static final String[] COLORS = {"blue", "purple", "green", "orange", "graphite", "cyan", "rose"};
    private static final String[] VISIBILITY = {"always", "hover_or_alt", "hidden"};

    private final Screen parent;
    private Page page = Page.GENERAL;
    private Binding pendingBinding;
    private Component status;
    private int left;
    private int panelWidth;
    private int col;
    private int x1;
    private int x2;

    public TabbyConfigScreen(Screen parent) {
        super(Component.translatable("tabby.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(520, Math.max(280, this.width - 24));
        left = (this.width - panelWidth) / 2;
        col = Math.max(112, (panelWidth - 44) / 2);
        x1 = left + 18;
        x2 = x1 + col + 8;

        int nav = (panelWidth - 48) / 3;
        addPage(Page.GENERAL, left + 12, 48, nav);
        addPage(Page.APPEARANCE, left + 18 + nav, 48, nav);
        addPage(Page.PERFORMANCE, left + 24 + nav * 2, 48, nav);

        switch (page) {
            case GENERAL -> generalPage();
            case APPEARANCE -> appearancePage();
            case PERFORMANCE -> performancePage();
        }

        addRenderableWidget(Button.builder(Component.translatable("tabby.settings.done"),
            b -> onClose()).bounds(this.width / 2 - 90, this.height - 32, 180, 20).build());
    }

    private void addPage(Page target, int x, int y, int w) {
        String key = "tabby.settings.page." + (page == target ? "active." : "") + target.key;
        addRenderableWidget(Button.builder(Component.translatable(key), b -> {
            page = target;
            pendingBinding = null;
            rebuildWidgets();
        }).bounds(x, y, w, 22).build());
    }

    private void generalPage() {
        option(x1, 104, col, toggle("tabby.settings.restore_addresses", TabbyConfig.restoreServerAddresses()),
            () -> TabbyConfig.setRestoreServerAddresses(!TabbyConfig.restoreServerAddresses()));
        option(x2, 104, col, toggle("tabby.settings.show_menu_tabs", TabbyConfig.showInMenus()),
            () -> TabbyConfig.setShowInMenus(!TabbyConfig.showInMenus()));
        option(x1, 134, col, toggle("tabby.settings.damage_alerts", TabbyConfig.notifyBackgroundDamage()),
            () -> TabbyConfig.setNotifyBackgroundDamage(!TabbyConfig.notifyBackgroundDamage()));
        keyOption(x2, 134, Binding.CREATE);
        keyOption(x1, 164, Binding.CLOSE);
        keyOption(x2, 164, Binding.NEXT);
        option(x1, 194, col, toggle("tabby.settings.unread_messages", TabbyConfig.showUnreadMessages()),
            () -> TabbyConfig.setShowUnreadMessages(!TabbyConfig.showUnreadMessages()));
        option(x2, 194, col, toggle("tabby.settings.auto_reconnect", TabbyConfig.autoReconnect()),
            () -> TabbyConfig.setAutoReconnect(!TabbyConfig.autoReconnect()));
    }

    private void appearancePage() {
        option(left + 12, 104, panelWidth - 24, Component.translatable("tabby.settings.title_mode",
            Component.translatable("tabby.settings.title_mode." + TabbyConfig.tabTitleMode())),
            () -> TabbyConfig.setTabTitleMode("address".equals(TabbyConfig.tabTitleMode()) ? "server_name" : "address"));
        option(left + 12, 134, panelWidth - 24, Component.translatable("tabby.settings.bar_visibility",
            Component.translatable("tabby.settings.bar_visibility." + TabbyConfig.barVisibilityMode())),
            this::cycleVisibility);
        option(x1, 164, col, toggle("tabby.settings.show_ping", TabbyConfig.showPing()),
            () -> TabbyConfig.setShowPing(!TabbyConfig.showPing()));
        option(x2, 164, col, toggle("tabby.settings.show_memory_label", TabbyConfig.showTabMemoryLabel()),
            () -> TabbyConfig.setShowTabMemoryLabel(!TabbyConfig.showTabMemoryLabel()));
        option(x1, 194, col, Component.translatable("tabby.settings.bar_size", TabbyConfig.barScalePercent()),
            this::cycleSize);
        option(x2, 194, col, Component.translatable("tabby.settings.bar_color",
            Component.translatable("tabby.settings.color." + TabbyConfig.barColorPreset())), this::cycleColor);
        option(left + 12, 224, panelWidth - 24,
            Component.translatable("tabby.settings.bar_opacity", TabbyConfig.barOpacityPercent()), this::cycleOpacity);
        option(left + 12, 254, panelWidth - 24,
            toggle("tabby.settings.damage_flash", TabbyConfig.showDamageFlash()),
            () -> TabbyConfig.setShowDamageFlash(!TabbyConfig.showDamageFlash()));
        option(left + 12, 284, panelWidth - 24,
            toggle("tabby.settings.tab_vitals", TabbyConfig.showTabVitals()),
            () -> TabbyConfig.setShowTabVitals(!TabbyConfig.showTabVitals()));
    }

    private void performancePage() {
        option(left + 12, 104, width - 24,
            Component.translatable("tabby.settings.tab_limit", TabbyConfig.maxBackgroundTabs()), this::cycleLimit);
        option(left + 12, 134, width - 24, toggle("tabby.settings.hud_info", TabbyConfig.showHudInfo()),
            () -> TabbyConfig.setShowHudInfo(!TabbyConfig.showHudInfo()));
    }

    private void option(int x, int y, int w, Component label, Runnable action) {
        addRenderableWidget(Button.builder(label, b -> {
            action.run();
            status = Component.translatable("tabby.settings.saved");
            rebuildWidgets();
        }).bounds(x, y, w, 22).build());
    }

    private void keyOption(int x, int y, Binding binding) {
        Component label = pendingBinding == binding
            ? Component.translatable("tabby.settings.key.waiting_button", bindingName(binding))
            : Component.translatable("tabby.settings.key.binding", bindingName(binding),
                keyName(keyFor(binding)));
        addRenderableWidget(Button.builder(label, b -> {
            pendingBinding = binding;
            status = Component.translatable("tabby.settings.key.waiting");
            rebuildWidgets();
        }).bounds(x, y, col, 22).build());
    }

    private Component toggle(String key, boolean enabled) {
        return Component.translatable(key,
            Component.translatable(enabled ? "tabby.settings.value.on" : "tabby.settings.value.off"));
    }

    private int keyFor(Binding b) {
        return switch (b) {
            case CREATE -> TabbyConfig.keyCreateTab();
            case CLOSE -> TabbyConfig.keyCloseTab();
            case NEXT -> TabbyConfig.keyNextTab();
        };
    }

    private Component bindingName(Binding b) {
        return Component.translatable("tabby.settings.key." + b.key);
    }

    private String keyName(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            default -> {
                String name = GLFW.glfwGetKeyName(key, 0);
                yield name == null ? "Key " + key : name.toUpperCase(Locale.ROOT);
            }
        };
    }

    private void cycleLimit() {
        int i = 0;
        for (int n = 0; n < LIMITS.length; n++) if (LIMITS[n] == TabbyConfig.maxBackgroundTabs()) i = n;
        TabbyConfig.setMaxBackgroundTabs(LIMITS[(i + 1) % LIMITS.length]);
    }

    private void cycleSize() {
        int i = 0;
        for (int n = 0; n < SIZES.length; n++) if (SIZES[n] == TabbyConfig.barScalePercent()) i = n;
        TabbyConfig.setBarScalePercent(SIZES[(i + 1) % SIZES.length]);
    }

    private void cycleColor() {
        int i = 0;
        for (int n = 0; n < COLORS.length; n++) if (COLORS[n].equals(TabbyConfig.barColorPreset())) i = n;
        TabbyConfig.setBarColorPreset(COLORS[(i + 1) % COLORS.length]);
    }

    private void cycleOpacity() {
        int[] values = {20, 40, 60, 80, 100};
        int i = 0;
        for (int n = 0; n < values.length; n++) if (values[n] == TabbyConfig.barOpacityPercent()) i = n;
        TabbyConfig.setBarOpacityPercent(values[(i + 1) % values.length]);
    }

    private void cycleVisibility() {
        int i = 0;
        for (int n = 0; n < VISIBILITY.length; n++) if (VISIBILITY[n].equals(TabbyConfig.barVisibilityMode())) i = n;
        TabbyConfig.setBarVisibilityMode(VISIBILITY[(i + 1) % VISIBILITY.length]);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (pendingBinding == null) return super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            pendingBinding = null;
            status = Component.translatable("tabby.settings.key.cancelled");
            rebuildWidgets();
            return true;
        }
        if (keyCode < GLFW.GLFW_KEY_SPACE || keyCode > GLFW.GLFW_KEY_LAST
            || keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL
            || (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9)) {
            status = Component.translatable("tabby.settings.key.invalid");
            return true;
        }
        if ((pendingBinding != Binding.CREATE && keyCode == TabbyConfig.keyCreateTab())
            || (pendingBinding != Binding.CLOSE && keyCode == TabbyConfig.keyCloseTab())
            || (pendingBinding != Binding.NEXT && keyCode == TabbyConfig.keyNextTab())) {
            status = Component.translatable("tabby.settings.key.duplicate");
            return true;
        }
        switch (pendingBinding) {
            case CREATE -> TabbyConfig.setKeyCreateTab(keyCode);
            case CLOSE -> TabbyConfig.setKeyCloseTab(keyCode);
            case NEXT -> TabbyConfig.setKeyNextTab(keyCode);
        }
        pendingBinding = null;
        status = Component.translatable("tabby.settings.saved");
        rebuildWidgets();
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, this.width, 42, 0xC910151D);
        g.fill(0, 40, this.width, 42, 0xFF69B7F0);
        g.fill(left, 80, left + 2, this.height - 42, 0xFF69B7F0);
        g.fill(left + panelWidth - 2, 80, left + panelWidth, this.height - 42, 0x553D566B);
        g.drawString(font, Component.translatable("tabby.settings.title"), left + 4, 10, 0xFFFFFFFF, false);
        g.drawString(font, Component.translatable("tabby.settings.subtitle"), left + 4, 25, 0xFFB8C5D1, false);
        g.drawString(font, Component.translatable("tabby.settings.section." + page.key), left + 12, 84, 0xFFE7EDF4, false);
        if (page == Page.PERFORMANCE) {
            g.drawString(font, Component.translatable("tabby.settings.memory_note"), left + 14, 170, 0xFFB8C5D1, false);
            g.drawString(font, Component.translatable("tabby.settings.memory_note_secondary"), left + 14, 184, 0xFF8F9EAC, false);
        } else if (page == Page.APPEARANCE) {
            g.drawString(font, Component.translatable("tabby.settings.appearance_note"), left + 14, 314, 0xFF8F9EAC, false);
        } else {
            g.drawString(font, Component.translatable("tabby.settings.keyboard_note"), left + 14, 226, 0xFF8F9EAC, false);
        }
        if (status != null) g.drawCenteredString(font, status, this.width / 2, this.height - 48, 0xFFE8D28B);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private enum Page {
        GENERAL("general"), APPEARANCE("appearance"), PERFORMANCE("performance");
        final String key;
        Page(String key) { this.key = key; }
    }

    private enum Binding {
        CREATE("create"), CLOSE("close"), NEXT("next");
        final String key;
        Binding(String key) { this.key = key; }
    }
}
