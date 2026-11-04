package dev.tabby.tab;

/** Verifies tab-title formatting independently from the renderer and UI. */
final class TabTitleDisplaySmokeTest {
    private TabTitleDisplaySmokeTest() {
    }

    static void verify(TabContext context) {
        if (context == null || context.serverData == null) {
            throw new IllegalStateException("Tab title smoke test requires a connected server context");
        }

        String previous = TabbyConfig.tabTitleMode();
        try {
            TabbyConfig.setTabTitleMode("address");
            String address = context.serverData.ip;
            if (address == null || !address.equals(context.displayTitle())) {
                throw new IllegalStateException("Address mode did not use the server address as the tab title");
            }

            TabbyConfig.setTabTitleMode("server_name");
            String name = context.serverData.name;
            if (name == null || !name.equals(context.displayTitle())) {
                throw new IllegalStateException("Server-name mode did not use the server-provided name");
            }
        } finally {
            TabbyConfig.setTabTitleMode(previous);
        }
    }
}
