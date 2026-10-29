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
    private static final int BAR_TOP = 4;
    private static final int BAR_HEIGHT = 25;
    private static final int LEFT_MARGIN = 5;
    private static final int GAP = 3;
    private static final int PLUS_WIDTH = 24;
    private static final int CLOSE_WIDTH = 20;
    private static final int MAX_TAB_WIDTH = 170;

    private static final int BAR_BACKGROUND = 0xE611161D;
    private static final int ACTIVE_BACKGROUND = 0xFF293B4B;
    private static final int INACTIVE_BACKGROUND = 0xFF202832;
    private static final int ACTIVE_ACCENT = 0xFF69B7F0;
    private static final int HOVER_ACCENT = 0xFF415B70;
    private static final int TEXT_COLOR = 0xFFF1F5F9;
    private static final int MUTED_TEXT_COLOR = 0xFFB6C1CC;
    private static final int CLOSE_HOVER_COLOR = 0xFF7A3540;

    private static boolean initialized;
    private static int draggingTabId = -1;
    private static double dragStartX;
    private static double dragStartY;
    private static boolean dragStarted;

    private TabBar() {
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
        graphics.fill(layout.left(), BAR_TOP - 2, layout.right(), BAR_TOP + BAR_HEIGHT + 2, BAR_BACKGROUND);

        for (TabSlot slot : layout.tabs()) {
            int right = slot.x() + slot.width();
            int bottom = BAR_TOP + BAR_HEIGHT;
            int background = slot.active() ? ACTIVE_BACKGROUND : INACTIVE_BACKGROUND;
            graphics.fill(slot.x(), BAR_TOP, right, bottom, background);
            if (slot.active()) {
                graphics.fill(slot.x(), BAR_TOP, right, BAR_TOP + 2, ACTIVE_ACCENT);
            }

            boolean closeHover = layout.closeX(slot) <= layout.mouseX()
                && layout.mouseX() < right
                && BAR_TOP <= layout.mouseY()
                && layout.mouseY() < bottom;
            if (closeHover) {
                graphics.fill(right - CLOSE_WIDTH, BAR_TOP + 2, right, bottom - 2, CLOSE_HOVER_COLOR);
            }

            int textY = BAR_TOP + (BAR_HEIGHT - client.font.lineHeight) / 2;
            int pinnedOffset = slot.pinned() ? 11 : 0;
            int pingWidth = slot.pingLabel().isEmpty() ? 0 : client.font.width(slot.pingLabel()) + 5;
            int titleMaxWidth = Math.max(10, slot.width() - CLOSE_WIDTH - 12 - pinnedOffset - pingWidth);
            String title = truncate(client, slot.title(), titleMaxWidth);

            if (slot.pinned()) {
                graphics.drawString(client.font, "●", slot.x() + 3, textY, ACTIVE_ACCENT, false);
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
                    right - CLOSE_WIDTH - client.font.width(slot.pingLabel()) - 3,
                    textY,
                    MUTED_TEXT_COLOR,
                    false
                );
            }
            graphics.drawString(
                client.font,
                "×",
                right - CLOSE_WIDTH + 5,
                textY,
                closeHover ? TEXT_COLOR : MUTED_TEXT_COLOR,
                false
            );
        }

        graphics.fill(layout.plusX(), BAR_TOP, layout.plusX() + PLUS_WIDTH, BAR_TOP + BAR_HEIGHT, HOVER_ACCENT);
        int plusWidth = client.font.width("+");
        graphics.drawString(
            client.font,
            "+",
            layout.plusX() + (PLUS_WIDTH - plusWidth) / 2,
            BAR_TOP + (BAR_HEIGHT - client.font.lineHeight) / 2,
            TEXT_COLOR,
            false
        );
    }

    public static boolean handleClick(Minecraft client, double mouseX, double mouseY, int button) {
        if (client == null
            || !isVisibleOnScreen(client.screen)
            || (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            return false;
        }

        if (draggingTabId >= 0
            && GLFW.glfwGetMouseButton(client.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            resetDrag();
        }

        List<TabContext> contexts = TabManager.contexts();
        if (contexts.isEmpty()) {
            return false;
        }

        Layout layout = layout(client, contexts);
        if (mouseY < BAR_TOP || mouseY >= BAR_TOP + BAR_HEIGHT
            || mouseX < layout.left() || mouseX >= layout.right()) {
            return false;
        }

        for (TabSlot slot : layout.tabs()) {
            if (mouseX < slot.x() || mouseX >= slot.x() + slot.width()) {
                continue;
            }

            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                TabManager.togglePinned(slot.id());
                return true;
            }
            if (mouseX >= layout.closeX(slot)) {
                resetDrag();
                TabManager.closeTab(slot.id());
            } else {
                TabManager.switchTo(slot.id());
                draggingTabId = slot.id();
                dragStartX = mouseX;
                dragStartY = mouseY;
                dragStarted = false;
            }
            return true;
        }

        if (mouseX >= layout.plusX() && mouseX < layout.plusX() + PLUS_WIDTH) {
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
            screenWidth - LEFT_MARGIN * 2 - PLUS_WIDTH - GAP * (count + 1)
        );
        int tabWidth = count == 0
            ? 0
            : Math.max(1, Math.min(MAX_TAB_WIDTH, tabsWidthAvailable / count));

        List<TabSlot> slots = new ArrayList<>(count);
        int x = LEFT_MARGIN;
        for (TabContext context : contexts) {
            String pingLabel = "";
            if (context.serverData != null && context.serverData.ip != null && !context.serverData.ip.isBlank()) {
                pingLabel = context.serverData.status != null && context.serverData.ping >= 0
                    ? context.serverData.ping + "ms"
                    : "—";
            }
            slots.add(new TabSlot(
                context.id(),
                context.title(),
                x,
                tabWidth,
                context.id() == TabManager.activeId(),
                context.pinned,
                pingLabel
            ));
            x += tabWidth + GAP;
        }

        int plusX = x;
        int right = Math.min(screenWidth - LEFT_MARGIN, plusX + PLUS_WIDTH);
        int mouseX = (int) (client.mouseHandler.xpos() * client.getWindow().getGuiScaledWidth() / client.getWindow().getScreenWidth());
        int mouseY = (int) (client.mouseHandler.ypos() * client.getWindow().getGuiScaledHeight() / client.getWindow().getScreenHeight());
        return new Layout(LEFT_MARGIN, right, plusX, slots, mouseX, mouseY);
    }

    public static void handleMouseMove(Minecraft client, double rawMouseX, double rawMouseY) {
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

        long window = client.getWindow().getWindow();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            resetDrag();
            return;
        }

        double mouseX = rawMouseX * client.getWindow().getGuiScaledWidth() / client.getWindow().getScreenWidth();
        double mouseY = rawMouseY * client.getWindow().getGuiScaledHeight() / client.getWindow().getScreenHeight();
        if (!dragStarted && Math.hypot(mouseX - dragStartX, mouseY - dragStartY) >= 4.0) {
            dragStarted = true;
        }
        if (!dragStarted || mouseY < BAR_TOP || mouseY >= BAR_TOP + BAR_HEIGHT) {
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
            if (mouseX >= target.x() + target.width() / 2.0) {
                targetIndex++;
            }
            TabManager.moveTab(draggingTabId, targetIndex);
        }
    }

    public static void handleMouseRelease() {
        resetDrag();
    }

    public static boolean isDragging() {
        return dragStarted;
    }

    private static void resetDrag() {
        draggingTabId = -1;
        dragStartX = 0;
        dragStartY = 0;
        dragStarted = false;
    }

    static int stripCenterY() {
        return BAR_TOP + BAR_HEIGHT / 2;
    }

    static int plusButtonCenterX(Minecraft client) {
        return layout(client, TabManager.contexts()).plusX() + PLUS_WIDTH / 2;
    }

    static int closeButtonCenterX(Minecraft client, int tabId) {
        Layout layout = layout(client, TabManager.contexts());
        for (TabSlot slot : layout.tabs()) {
            if (slot.id() == tabId) {
                return layout.closeX(slot) + CLOSE_WIDTH / 2;
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

    private record TabSlot(int id, String title, int x, int width, boolean active, boolean pinned, String pingLabel) {
    }

    private record Layout(int left, int right, int plusX, List<TabSlot> tabs, int mouseX, int mouseY) {
        private int closeX(TabSlot slot) {
            return slot.x() + slot.width() - CLOSE_WIDTH;
        }
    }
}
