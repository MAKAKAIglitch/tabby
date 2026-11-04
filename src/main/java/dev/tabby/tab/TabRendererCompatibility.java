package dev.tabby.tab;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Isolates renderer ownership and optional Voxy lifecycle hooks from tab orchestration.
 * Voxy is detected reflectively and remains an optional runtime dependency.
 */
final class TabRendererCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");

    private TabRendererCompatibility() {
    }

    static boolean shouldRenderRenderer(
        LevelRenderer renderer,
        boolean clientAvailable,
        int activeId,
        TabContext active,
        boolean backgroundContext
    ) {
        if (renderer == null || !clientAvailable || activeId < 0) {
            return true;
        }
        if (backgroundContext) {
            return false;
        }
        return active == null || active.levelRenderer == renderer;
    }

    static boolean shouldDeferVoxyRendererCreation(
        LevelRenderer renderer,
        boolean clientAvailable,
        int activeId,
        TabContext active,
        boolean backgroundContext
    ) {
        if (!clientAvailable || renderer == null) {
            return false;
        }
        if (backgroundContext) {
            return true;
        }
        return active != null && active.levelRenderer != null && active.levelRenderer != renderer;
    }

    static void suspendVoxyRenderer(LevelRenderer renderer, int tabId) {
        if (renderer == null) {
            return;
        }

        try {
            Object voxyRenderer = renderer.getClass().getMethod("voxy$getRenderSystem").invoke(renderer);
            if (voxyRenderer == null) {
                return;
            }

            renderer.getClass().getMethod("voxy$shutdownRenderer").invoke(renderer);
            LOGGER.info("Suspended Voxy render system for background tab {}", tabId);
        } catch (NoSuchMethodException ignored) {
            // Voxy is optional; its methods exist only when its runtime mixin is applied.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not suspend Voxy render system for tab {}; keeping the client running", tabId, exception);
        }
    }

    static void resumeVoxyRenderer(LevelRenderer renderer, ClientLevel level, int tabId) {
        if (renderer == null || level == null) {
            return;
        }

        try {
            Object voxyRenderer = renderer.getClass().getMethod("voxy$getRenderSystem").invoke(renderer);
            if (voxyRenderer != null) {
                return;
            }

            renderer.getClass().getMethod("voxy$createRenderer").invoke(renderer);
            LOGGER.info("Activated Voxy render system for active tab {}", tabId);
        } catch (NoSuchMethodException ignored) {
            // Voxy is optional; its methods exist only when its runtime mixin is applied.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not activate Voxy render system for tab {}; keeping the client running", tabId, exception);
        }
    }
}
