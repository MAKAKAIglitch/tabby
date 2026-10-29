package dev.tabby.tab;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Native Minecraft settings screen opened by Mod Menu.
 */
public final class TabbyConfigScreen extends Screen {
    private static final int[] TAB_LIMITS = {1, 2, 3, 4, 6, 8, 12, 16};

    private final Screen parent;
    private Binding pendingBinding;
    private Component statusMessage;
    private Button tabLimitButton;
    private Button restoreAddressesButton;
    private Button hudInfoButton;
    private Button barSizeButton;
    private Button barColorButton;
    private Button barOpacityButton;
    private Button createShortcutButton;
    private Button closeShortcutButton;
    private Button nextShortcutButton;

    public TabbyConfigScreen(Screen parent) {
        super(Component.literal("Tabby Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int wideX = centerX - 130;

        this.tabLimitButton = this.addRenderableWidget(Button.builder(
            this.tabLimitMessage(),
            button -> this.cycleTabLimit()
        ).bounds(wideX, 48, 260, 20).build());

        this.restoreAddressesButton = this.addRenderableWidget(Button.builder(
            this.restoreAddressesMessage(),
            button -> {
                TabbyConfig.setRestoreServerAddresses(!TabbyConfig.restoreServerAddresses());
                this.statusMessage = Component.literal("Saved.");
                this.refreshMessages();
            }
        ).bounds(wideX, 73, 260, 20).build());

        int halfWidth = 127;
        this.hudInfoButton = this.addRenderableWidget(Button.builder(
            this.hudInfoMessage(),
            button -> {
                TabbyConfig.setShowHudInfo(!TabbyConfig.showHudInfo());
                this.statusMessage = Component.literal("Saved.");
                this.refreshMessages();
            }
        ).bounds(centerX - 130, 98, halfWidth, 20).build());

        this.barSizeButton = this.addRenderableWidget(Button.builder(
            this.barSizeMessage(),
            button -> this.cycleBarSize()
        ).bounds(centerX + 3, 98, halfWidth, 20).build());

        this.barColorButton = this.addRenderableWidget(Button.builder(
            this.barColorMessage(),
            button -> this.cycleBarColor()
        ).bounds(centerX - 130, 122, halfWidth, 20).build());

        this.barOpacityButton = this.addRenderableWidget(Button.builder(
            this.barOpacityMessage(),
            button -> this.cycleBarOpacity()
        ).bounds(centerX + 3, 122, halfWidth, 20).build());

        this.createShortcutButton = this.addRenderableWidget(this.keyButton(
            centerX - 130, 150, halfWidth, Binding.CREATE_TAB));
        this.closeShortcutButton = this.addRenderableWidget(this.keyButton(
            centerX + 3, 150, halfWidth, Binding.CLOSE_TAB));
        this.nextShortcutButton = this.addRenderableWidget(this.keyButton(
            centerX - 130, 176, halfWidth, Binding.NEXT_TAB));

        this.addRenderableWidget(Button.builder(
            Component.literal("Done"),
            button -> this.onClose()
        ).bounds(centerX - 100, this.height - 30, 200, 20).build());

        this.refreshMessages();
    }

    private Button keyButton(int x, int y, int width, Binding binding) {
        return Button.builder(this.bindingMessage(binding), button -> {
            this.pendingBinding = binding;
            this.statusMessage = Component.literal("Press a key for this shortcut. Escape cancels.");
            this.refreshMessages();
        }).bounds(x, y, width, 20).build();
    }

    private void cycleTabLimit() {
        int current = TabbyConfig.maxBackgroundTabs();
        int next = TAB_LIMITS[0];
        for (int index = 0; index < TAB_LIMITS.length; index++) {
            if (TAB_LIMITS[index] == current) {
                next = TAB_LIMITS[(index + 1) % TAB_LIMITS.length];
                break;
            }
        }
        TabbyConfig.setMaxBackgroundTabs(next);
        this.statusMessage = Component.literal("Saved.");
        this.refreshMessages();
    }

    private Component tabLimitMessage() {
        return Component.literal("Maximum background tabs: " + TabbyConfig.maxBackgroundTabs());
    }

    private Component restoreAddressesMessage() {
        return Component.literal("Restore server addresses: "
            + (TabbyConfig.restoreServerAddresses() ? "On" : "Off"));
    }

    private void cycleBarSize() {
        int[] values = {75, 85, 100, 115, 125};
        int current = TabbyConfig.barScalePercent();
        int next = values[0];
        for (int index = 0; index < values.length; index++) {
            if (values[index] == current) {
                next = values[(index + 1) % values.length];
                break;
            }
        }
        TabbyConfig.setBarScalePercent(next);
        this.statusMessage = Component.literal("Saved.");
        this.refreshMessages();
    }

    private void cycleBarColor() {
        String[] values = {"blue", "purple", "green", "orange", "graphite"};
        String current = TabbyConfig.barColorPreset();
        int currentIndex = 0;
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(current)) {
                currentIndex = index;
                break;
            }
        }
        TabbyConfig.setBarColorPreset(values[(currentIndex + 1) % values.length]);
        this.statusMessage = Component.literal("Saved.");
        this.refreshMessages();
    }

    private void cycleBarOpacity() {
        int[] values = {20, 40, 60, 80, 100};
        int current = TabbyConfig.barOpacityPercent();
        int next = values[values.length - 1];
        for (int index = 0; index < values.length; index++) {
            if (values[index] == current) {
                next = values[(index + 1) % values.length];
                break;
            }
            if (index + 1 < values.length && current > values[index] && current < values[index + 1]) {
                next = values[index + 1];
                break;
            }
        }
        TabbyConfig.setBarOpacityPercent(next);
        this.statusMessage = Component.literal("Saved.");
        this.refreshMessages();
    }

    private Component hudInfoMessage() {
        return Component.literal("HUD info plaque: " + (TabbyConfig.showHudInfo() ? "On" : "Off"));
    }

    private Component barSizeMessage() {
        return Component.literal("Tab bar size: " + TabbyConfig.barScalePercent() + "%");
    }

    private Component barColorMessage() {
        return Component.literal("Tab bar color: " + capitalize(TabbyConfig.barColorPreset()));
    }

    private Component barOpacityMessage() {
        return Component.literal("Tab bar opacity: " + TabbyConfig.barOpacityPercent() + "%");
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Blue";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private Component bindingMessage(Binding binding) {
        if (this.pendingBinding == binding) {
            return Component.literal(this.bindingName(binding) + ": Press a key...");
        }

        int key = switch (binding) {
            case CREATE_TAB -> TabbyConfig.keyCreateTab();
            case CLOSE_TAB -> TabbyConfig.keyCloseTab();
            case NEXT_TAB -> TabbyConfig.keyNextTab();
        };
        return Component.literal(this.bindingName(binding) + ": Ctrl+" + this.keyName(key));
    }

    private String bindingName(Binding binding) {
        return switch (binding) {
            case CREATE_TAB -> "Create tab";
            case CLOSE_TAB -> "Close tab";
            case NEXT_TAB -> "Next tab";
        };
    }

    private String keyName(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            case GLFW.GLFW_KEY_LEFT -> "Left";
            case GLFW.GLFW_KEY_RIGHT -> "Right";
            case GLFW.GLFW_KEY_UP -> "Up";
            case GLFW.GLFW_KEY_DOWN -> "Down";
            default -> {
                String name = GLFW.glfwGetKeyName(key, 0);
                yield name == null ? "Key " + key : name.toUpperCase(Locale.ROOT);
            }
        };
    }

    private void refreshMessages() {
        if (this.tabLimitButton != null) {
            this.tabLimitButton.setMessage(this.tabLimitMessage());
        }
        if (this.restoreAddressesButton != null) {
            this.restoreAddressesButton.setMessage(this.restoreAddressesMessage());
        }
        if (this.hudInfoButton != null) {
            this.hudInfoButton.setMessage(this.hudInfoMessage());
        }
        if (this.barSizeButton != null) {
            this.barSizeButton.setMessage(this.barSizeMessage());
        }
        if (this.barColorButton != null) {
            this.barColorButton.setMessage(this.barColorMessage());
        }
        if (this.barOpacityButton != null) {
            this.barOpacityButton.setMessage(this.barOpacityMessage());
        }
        if (this.createShortcutButton != null) {
            this.createShortcutButton.setMessage(this.bindingMessage(Binding.CREATE_TAB));
        }
        if (this.closeShortcutButton != null) {
            this.closeShortcutButton.setMessage(this.bindingMessage(Binding.CLOSE_TAB));
        }
        if (this.nextShortcutButton != null) {
            this.nextShortcutButton.setMessage(this.bindingMessage(Binding.NEXT_TAB));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.pendingBinding == null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.pendingBinding = null;
            this.statusMessage = Component.literal("Shortcut change cancelled.");
            this.refreshMessages();
            return true;
        }

        if (keyCode < GLFW.GLFW_KEY_SPACE
            || keyCode > GLFW.GLFW_KEY_LAST
            || keyCode == GLFW.GLFW_KEY_LEFT_CONTROL
            || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL
            || (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9)) {
            this.statusMessage = Component.literal("Choose a non-reserved key; Ctrl is applied automatically.");
            return true;
        }

        if (this.isAlreadyAssigned(keyCode, this.pendingBinding)) {
            this.statusMessage = Component.literal("That key is already assigned to another Tabby shortcut.");
            return true;
        }

        String savedName = this.bindingName(this.pendingBinding);
        switch (this.pendingBinding) {
            case CREATE_TAB -> TabbyConfig.setKeyCreateTab(keyCode);
            case CLOSE_TAB -> TabbyConfig.setKeyCloseTab(keyCode);
            case NEXT_TAB -> TabbyConfig.setKeyNextTab(keyCode);
        }
        this.pendingBinding = null;
        this.statusMessage = Component.literal(savedName + " shortcut saved.");
        this.refreshMessages();
        return true;
    }

    private boolean isAlreadyAssigned(int keyCode, Binding binding) {
        return switch (binding) {
            case CREATE_TAB -> keyCode == TabbyConfig.keyCloseTab() || keyCode == TabbyConfig.keyNextTab();
            case CLOSE_TAB -> keyCode == TabbyConfig.keyCreateTab() || keyCode == TabbyConfig.keyNextTab();
            case NEXT_TAB -> keyCode == TabbyConfig.keyCreateTab() || keyCode == TabbyConfig.keyCloseTab();
        };
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        graphics.drawCenteredString(
            this.font,
            "Tabs appear on title/pause screens; Ctrl+1..9 selects by position.",
            this.width / 2,
            34,
            0xC4D5E2
        );
        if (this.statusMessage != null) {
            graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2, this.height - 40, 0xE8D28B);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private enum Binding {
        CREATE_TAB,
        CLOSE_TAB,
        NEXT_TAB
    }
}
