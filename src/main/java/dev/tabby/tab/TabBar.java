package dev.tabby.tab;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws and handles Tabby's browser-style tab strip.
 */
public final class TabBar {
    private static final int BASE_BAR_TOP = 4;
    private static final int BASE_BAR_HEIGHT = 25;
    private static final int BASE_LEFT_MARGIN = 5;
    private static final int BASE_GAP = 3;
    private static final int BASE_PLUS_WIDTH = 24;
    private static final int BASE_CLOSE_WIDTH = 20;
    private static final int BASE_MAX_TAB_WIDTH = 170;

    private static final int BASE_BAR_BACKGROUND = 0xE611161D;
    private static final int BASE_ACTIVE_BACKGROUND = 0xFF293B4B;
    private static final int BASE_INACTIVE_BACKGROUND = 0xFF202832;
    private static final int BASE_ACTIVE_ACCENT = 0xFF69B7F0;
    private static final int BASE_HOVER_ACCENT = 0xFF415B70;
    private static final int TEXT_COLOR = 0xFFF1F5F9;
    private static final int MUTED_TEXT_COLOR = 0xFFB6C1CC;
    private static final int BASE_CLOSE_HOVER_COLOR = 0xFF7A3540;

    private static boolean initialized;
    private static int draggingTabId = -1;
    private static int pendingClickTabId = -1;
    private static int lastDropTargetId = -1;
    private static boolean lastDropAfterTarget;
    private static double dragStartX;
    private static double dragStartY;
    private static boolean dragStarted;

    private TabBar() {
    }

    private static int scaled(int value) {
        return Math.max(1, Math.round(value * TabbyConfig.barScalePercent() / 100.0f));
    }

    private static int barTop() { return scaled(BASE_BAR_TOP); }
    private static int barHeight() { return scaled(BASE_BAR_HEIGHT); }
    private static int leftMargin() { return scaled(BASE_LEFT_MARGIN); }
    private static int gap() { return scaled(BASE_GAP); }
    private static int plusWidth() { return scaled(BASE_PLUS_WIDTH); }
    private static int closeWidth() { return scaled(BASE_CLOSE_WIDTH); }
    private static int maxTabWidth() { return scaled(BASE_MAX_TAB_WIDTH); }

    private static int withOpacity(int argb) {
        int opacity = TabbyConfig.barOpacityPercent();
        int alpha = (int) Math.round(255.0 * opacity / 100.0);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static int activeBackgroundColor() {
        return switch (TabbyConfig.barColorPreset()) {
            case "purple" -> 0xFF3D2F50;
            case "green" -> 0xFF24463A;
            case "orange" -> 0xFF4A3525;
            case "graphite" -> 0xFF363B43;
            default -> BASE_ACTIVE_BACKGROUND;
        };
    }

    private static int inactiveBackgroundColor() {
        return switch (TabbyConfig.barColorPreset()) {
            case "purple" -> 0xFF282033;
            case "green" -> 0xFF1C3029;
            case "orange" -> 0xFF33271E;
            case "graphite" -> 0xFF25292F;
            default -> BASE_INACTIVE_BACKGROUND;
        };
    }

    private static int activeAccentColor() {
        return switch (TabbyConfig.barColorPreset()) {
            case "purple" -> 0xFFCB9BFF;
            case "green" -> 0xFF77D6A6;
            case "orange" -> 0xFFFFB36C;
            case "graphite" -> 0xFFD0D7E0;
            default -> BASE_ACTIVE_ACCENT;
        };
    }

    private static int hoverAccentColor() {
        return switch (TabbyConfig.barColorPreset()) {
            case "purple" -> 0xFF594171;
            case "green" -> 0xFF35624E;
            case "orange" -> 0xFF6A4A2F;
            case "graphite" -> 0xFF4A515B;
            default -> BASE_HOVER_ACCENT;
        };
    }

    private static int closeHoverColor() {
        return switch (TabbyConfig.barColorPreset()) {
            case "purple" -> 0xFF723B52;
            case "green" -> 0xFF365D4A;
            case "orange" -> 0xFF794224;
            case "graphite" -> 0xFF5A3C44;
            default -> BASE_CLOSE_HOVER_COLOR;
        };
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!isVisibleOnScreen(screen)) {
                return;
            }

            ScreenEvents.afterRender(screen).register(
                (renderedScreen, graphics, mouseX, mouseY, tickDelta) -> {
                    if (isVisibleOnScreen(renderedScreen)) {
                        render(client, graphics);
                    }
                }
            );
            ScreenMouseEvents.allowMouseClick(screen).register(
                (clickedScreen, mouseX, mouseY, button) ->
                    !isVisibleOnScreen(clickedScreen) || !handleClick(client, mouseX, mouseY, button)
            );
        });
    }

    public static boolean isVisibleOnScreen(Screen screen) {
        return screen instanceof TitleScreen || screen instanceof PauseScreen;
    }

    public static void render(Minecraft client, GuiGraphics graphics) {
        if (client == null || !isVisibleOnScreen(client.screen)) {
            return;
        }
        List<TabContext> contexts = TabManager.contexts();
        if (contexts.isEmpty()) {
            return;
        }

        Layout layout = layout(client, contexts);
        graphics.fill(layout.left(), barTop() - 2, layout.right(), barTop() + barHeight() + 2, withOpacity(BASE_BAR_BACKGROUND));

        for (TabSlot slot : layout.tabs()) {
            int right = slot.x() + slot.width();
            int bottom = barTop() + barHeight();
            int background = slot.active() ? withOpacity(activeBackgroundColor()) : withOpacity(inactiveBackgroundColor());
            graphics.fill(slot.x(), barTop(), right, bottom, background);
            if (slot.active()) {
                graphics.fill(slot.x(), barTop(), right, barTop() + 2, activeAccentColor());
            }
            if (dragStarted && slot.id() == draggingTabId) {
                graphics.fill(slot.x(), bottom - 2, right, bottom, activeAccentColor());
            }

            boolean closeHover = layout.closeX(slot) <= layout.mouseX()
                && layout.mouseX() < right
                && barTop() <= layout.mouseY()
                && layout.mouseY() < bottom;
            if (closeHover) {
                graphics.fill(right - closeWidth(), barTop() + 2, right, bottom - 2, withOpacity(closeHoverColor()));
            }

            int textY = barTop() + (barHeight() - client.font.lineHeight) / 2;
            int pinnedOffset = slot.pinned() ? 11 : 0;
            int pingWidth = slot.pingLabel().isEmpty() ? 0 : client.font.width(slot.pingLabel()) + 5;
            int memoryWidth = slot.memoryLabel().isEmpty() ? 0 : client.font.width(slot.memoryLabel()) + 5;
            int titleMaxWidth = Math.max(
                10,
                slot.width() - closeWidth() - 12 - pinnedOffset - pingWidth - memoryWidth - 4
            );
            String title = truncate(client, slot.title(), titleMaxWidth);

            if (slot.pinned()) {
                graphics.drawString(client.font, "●", slot.x() + 3, textY, activeAccentColor(), false);
            }
            graphics.drawString(
                client.font,
                title,
                slot.x() + 6 + pinnedOffset,
                textY,
                slot.active() ? TEXT_COLOR : MUTED_TEXT_COLOR,
                false
            );
            if (!slot.pingLabel().isEmpty()) {
                graphics.drawString(
                    client.font,
                    slot.pingLabel(),
                    right - closeWidth() - client.font.width(slot.pingLabel()) - 3,
                    textY,
                    MUTED_TEXT_COLOR,
                    false
                );
            }
            if (!slot.memoryLabel().isEmpty()) {
                graphics.drawString(
                    client.font,
                    slot.memoryLabel(),
                    right - closeWidth() - pingWidth - client.font.width(slot.memoryLabel()) - 4,
                    textY,
                    activeAccentColor(),
                    false
                );
            }
            graphics.drawString(
                client.font,
                "×",
                right - closeWidth() + 5,
                textY,
                closeHover ? TEXT_COLOR : MUTED_TEXT_COLOR,
                false
            );
        }

        graphics.fill(layout.plusX(), barTop(), layout.plusX() + plusWidth(), barTop() + barHeight(), withOpacity(hoverAccentColor()));
        int plusWidth = client.font.width("+");
        graphics.drawString(
            client.font,
            "+",
            layout.plusX() + (plusWidth() - plusWidth) / 2,
            barTop() + (barHeight() - client.font.lineHeight) / 2,
            TEXT_COLOR,
            false
        );
    }

    public static void renderHudMemory(Minecraft client, GuiGraphics graphics) {
        TabContext active = TabManager.activeContext();
        if (!TabbyConfig.showHudInfo() || client == null || active == null || client.level == null) {
            return;
        }

        TabMemoryUsage.Sample sample = TabMemoryUsage.sample(active);
        String label = sample.hudLabel(active.title());
        int padding = 5;
        int rightPadding = 7;
        int textWidth = client.font.width(label);
        int textHeight = client.font.lineHeight;
        int x = Math.max(4, client.getWindow().getGuiScaledWidth() - textWidth - rightPadding);
        int y = 5;
        graphics.fill(x - padding, y - 3, x + textWidth + padding, y + textHeight + 3, 0xE611161D);
        graphics.drawString(client.font, label, x, y, 0xFFF1F5F9, false);
    }

    public static boolean handleClick(Minecraft client, double mouseX, double mouseY, int button) {
        if (client == null
            || !isVisibleOnScreen(client.screen)
            || (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            return false;
        }

        if (draggingTabId >= 0
            && GLFW.glfwGetMouseButton(client.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            handleMouseRelease(client);
        }

        List<TabContext> contexts = TabManager.contexts();
        if (contexts.isEmpty()) {
            return false;
        }

        Layout layout = layout(client, contexts);
        if (mouseY < barTop() || mouseY >= barTop() + barHeight()
            || mouseX < layout.left() || mouseX >= layout.right()) {
            return false;
        }

        for (TabSlot slot : layout.tabs()) {
            if (mouseX < slot.x() || mouseX >= slot.x() + slot.width()) {
                continue;
            }

            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                resetDrag();
                TabManager.togglePinned(slot.id());
                return true;
            }
            if (mouseX >= layout.closeX(slot)) {
                resetDrag();
                TabManager.closeTab(slot.id());
            } else {
                draggingTabId = slot.id();
                pendingClickTabId = slot.id();
                dragStartX = mouseX;
                dragStartY = mouseY;
                dragStarted = false;
                lastDropTargetId = -1;
                lastDropAfterTarget = false;
            }
            return true;
        }

        if (mouseX >= layout.plusX() && mouseX < layout.plusX() + plusWidth()) {
            resetDrag();
            TabManager.createTab();
            return true;
        }

        // Gaps between tabs belong to the strip too: do not let the click leak to the game.
        return true;
    }

    private static Layout layout(Minecraft client, List<TabContext> contexts) {
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int count = contexts.size();
        int tabsWidthAvailable = Math.max(
            count,
            screenWidth - leftMargin() * 2 - plusWidth() - gap() * (count + 1)
        );
        int tabWidth = count == 0
            ? 0
            : Math.max(1, Math.min(maxTabWidth(), tabsWidthAvailable / count));

        List<TabSlot> slots = new ArrayList<>(count);
        int x = leftMargin();
        for (TabContext context : contexts) {
            String pingLabel = "";
            if (context.serverData != null && context.serverData.ip != null && !context.serverData.ip.isBlank()) {
                pingLabel = context.serverData.status != null && context.serverData.ping >= 0
                    ? context.serverData.ping + "ms"
                    : "—";
            }
            boolean active = context.id() == TabManager.activeId();
            String memoryLabel = active && TabbyConfig.showHudInfo() ? TabMemoryUsage.sample(context).compactLabel() : "";
            slots.add(new TabSlot(
                context.id(),
                context.title(),
                x,
                tabWidth,
                active,
                context.pinned,
                pingLabel,
                memoryLabel
            ));
            x += tabWidth + gap();
        }

        int plusX = x;
        int right = Math.min(screenWidth - leftMargin(), plusX + plusWidth());
        int mouseX = (int) (client.mouseHandler.xpos() * client.getWindow().getGuiScaledWidth() / client.getWindow().getScreenWidth());
        int mouseY = (int) (client.mouseHandler.ypos() * client.getWindow().getGuiScaledHeight() / client.getWindow().getScreenHeight());
        return new Layout(leftMargin(), right, plusX, slots, mouseX, mouseY);
    }

    public static void handleMouseMove(Minecraft client, double rawMouseX, double rawMouseY) {
        if (client == null) {
            return;
        }
        long window = client.getWindow().getWindow();
        boolean leftButtonDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        handleMouseMove(client, rawMouseX, rawMouseY, leftButtonDown);
    }

    /**
     * Processes a pointer move. The explicit button state also lets the smoke test
     * exercise the exact drag path without relying on synthetic native GLFW input.
     */
    public static void handleMouseMove(
        Minecraft client,
        double rawMouseX,
        double rawMouseY,
        boolean leftButtonDown
    ) {
        if (client == null) {
            return;
        }
        if (!isVisibleOnScreen(client.screen)) {
            resetDrag();
            return;
        }
        if (draggingTabId < 0) {
            return;
        }
        if (!leftButtonDown) {
            handleMouseRelease(client);
            return;
        }

        double mouseX = rawMouseX * client.getWindow().getGuiScaledWidth() / client.getWindow().getScreenWidth();
        double mouseY = rawMouseY * client.getWindow().getGuiScaledHeight() / client.getWindow().getScreenHeight();
        if (!dragStarted && Math.hypot(mouseX - dragStartX, mouseY - dragStartY) >= 4.0) {
            dragStarted = true;
        }
        if (!dragStarted || mouseY < barTop() || mouseY >= barTop() + barHeight()) {
            return;
        }

        List<TabContext> contexts = TabManager.contexts();
        if (contexts.size() < 2) {
            return;
        }
        Layout layout = layout(client, contexts);
        TabSlot target = null;
        for (TabSlot slot : layout.tabs()) {
            if (mouseX >= slot.x() && mouseX < slot.x() + slot.width()) {
                target = slot;
                break;
            }
        }
        if (target == null || target.id() == draggingTabId) {
            lastDropTargetId = -1;
            return;
        }

        int targetIndex = -1;
        for (int i = 0; i < contexts.size(); i++) {
            if (contexts.get(i).id() == target.id()) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex >= 0) {
            boolean afterTarget = mouseX >= target.x() + target.width() / 2.0;
            if (target.id() != lastDropTargetId || afterTarget != lastDropAfterTarget) {
                if (afterTarget) {
                    targetIndex++;
                }
                TabManager.moveTab(draggingTabId, targetIndex);
                lastDropTargetId = target.id();
                lastDropAfterTarget = afterTarget;
            }
        }
    }

    public static void handleMouseRelease(Minecraft client) {
        int clickedTabId = pendingClickTabId;
        boolean wasDrag = dragStarted;
        resetDrag();
        if (client != null && clickedTabId >= 0 && !wasDrag) {
            TabManager.switchTo(clickedTabId);
        }
    }

    public static void handleMouseRelease() {
        handleMouseRelease(Minecraft.getInstance());
    }

    public static boolean isDragging() {
        return dragStarted;
    }

    private static void resetDrag() {
        draggingTabId = -1;
        pendingClickTabId = -1;
        lastDropTargetId = -1;
        lastDropAfterTarget = false;
        dragStartX = 0;
        dragStartY = 0;
        dragStarted = false;
    }

    static int stripCenterY() {
        return barTop() + barHeight() / 2;
    }

    static int tabCenterX(Minecraft client, int tabId) {
        for (TabSlot slot : layout(client, TabManager.contexts()).tabs()) {
            if (slot.id() == tabId) {
                return slot.x() + slot.width() / 2;
            }
        }
        return -1;
    }

    static int plusButtonCenterX(Minecraft client) {
        return layout(client, TabManager.contexts()).plusX() + plusWidth() / 2;
    }

    static int closeButtonCenterX(Minecraft client, int tabId) {
        Layout layout = layout(client, TabManager.contexts());
        for (TabSlot slot : layout.tabs()) {
            if (slot.id() == tabId) {
                return layout.closeX(slot) + closeWidth() / 2;
            }
        }
        return -1;
    }

    private static String truncate(Minecraft client, String text, int maxWidth) {
        if (text == null || text.isBlank()) {
            return "Main Menu";
        }
        if (client.font.width(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        String candidate = text;
        while (!candidate.isEmpty() && client.font.width(candidate + ellipsis) > maxWidth) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate.isEmpty() ? ellipsis : candidate + ellipsis;
    }

    private record TabSlot(int id, String title, int x, int width, boolean active, boolean pinned, String pingLabel, String memoryLabel) {
    }

    private record Layout(int left, int right, int plusX, List<TabSlot> tabs, int mouseX, int mouseY) {
        private int closeX(TabSlot slot) {
            return slot.x() + slot.width() - closeWidth();
        }
    }
}
