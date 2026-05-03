package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.DebugFlagsState;

import java.util.List;

/**
 * Drilled into from {@link MainMenuScreen} as "Debug". Surfaces the
 * Wireframes sub-menu, the Chat Logs sub-menu, and the auto/manual spawn
 * switch — wireframes are exposed as a {@link CommandMenuEntry.DrillIn}
 * into {@link WireframesMenuScreen} so each overlay gets its own toggle
 * plus an "All On / All Off" master, and chat logs follow the same
 * pattern via {@link ChatLogsMenuScreen} for the spawn / collision chat
 * broadcasts. The Manual Spawn flag stays inline because there's only
 * one of it and it's the only non-grouped server-side debug toggle. All
 * flags live in {@link games.brennan.dungeontrain.debug.DebugFlags}
 * server-side and are mirrored on the client by {@link DebugFlagsState}.
 */
public final class DebugMenuScreen implements MenuScreen {

    @Override public String title() { return "Debug"; }

    @Override public List<CommandMenuEntry> entries() {
        boolean manual = DebugFlagsState.manualSpawnMode();
        return List.of(
            new CommandMenuEntry.DrillIn("Wireframes", new WireframesMenuScreen()),
            new CommandMenuEntry.DrillIn("Chat Logs", new ChatLogsMenuScreen()),
            new CommandMenuEntry.Toggle(
                "Manual Spawn (J)", manual,
                "dungeontrain debug spawnmode manual",
                "dungeontrain debug spawnmode auto"
            ),
            new CommandMenuEntry.Run("Debug Scan", "dungeontrain debug scan"),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
