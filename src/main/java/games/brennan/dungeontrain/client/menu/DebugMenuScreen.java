package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.DebugFlagsState;

import java.util.List;

/**
 * Drilled into from {@link MainMenuScreen} as "Debug". Surfaces the
 * gap-overlay wireframes toggle and the auto/manual spawn switch through
 * {@link CommandMenuEntry.Toggle} entries — both flags live in
 * {@link games.brennan.dungeontrain.debug.DebugFlags} server-side and are
 * mirrored on the client by
 * {@link DebugFlagsState} (kept in sync via
 * {@link games.brennan.dungeontrain.net.DebugFlagsPacket} on join + on every
 * change). The menu rebuilds entries each tick, so the toggle button's
 * {@code [ON]} / {@code [OFF]} suffix reflects the current server state
 * without an extra refresh hook.
 */
public final class DebugMenuScreen implements MenuScreen {

    @Override public String title() { return "Debug"; }

    @Override public List<CommandMenuEntry> entries() {
        boolean wireframes = DebugFlagsState.wireframesEnabled();
        boolean manual = DebugFlagsState.manualSpawnMode();
        return List.of(
            new CommandMenuEntry.Toggle(
                "Wireframes", wireframes,
                "dungeontrain debug wireframes on",
                "dungeontrain debug wireframes off"
            ),
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
