package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.DebugFlagsState;

import java.util.List;

/**
 * Drilled into from {@link DebugMenuScreen} as "Chat Logs". Lists the
 * chat-debug categories as individual {@link CommandMenuEntry.Toggle}
 * rows, with an "All On / All Off" {@link CommandMenuEntry.Split} master
 * at the top so the player can flip every category in one click.
 *
 * <p>Mirrors {@link WireframesMenuScreen} structurally: same Split-then-
 * Toggle-rows-then-Back layout, same {@link CommandMenuEntry.Stay}
 * variant on the master halves so the menu stays open and the per-row
 * states refresh on the next per-tick rebuild. Toggle rows stay full-
 * width so they get the green/grey row tint applied by
 * {@code CommandMenuRenderer} to top-level Toggle entries.</p>
 *
 * <p>Each Toggle reads its current state from {@link DebugFlagsState} —
 * the same client mirror the chat-emit gates ultimately sync against —
 * so the displayed state always matches what's actually being broadcast.</p>
 */
public final class ChatLogsMenuScreen implements MenuScreen {

    @Override public String title() { return "Chat Logs"; }

    @Override public List<CommandMenuEntry> entries() {
        boolean trainSpawn = DebugFlagsState.chatTrainSpawn();
        boolean collision = DebugFlagsState.chatCollision();

        return List.of(
            new CommandMenuEntry.Split(
                new CommandMenuEntry.Stay("All On", "dungeontrain debug chatlogs all on"),
                new CommandMenuEntry.Stay("All Off", "dungeontrain debug chatlogs all off"),
                0.50
            ),
            new CommandMenuEntry.Toggle(
                "Train Spawn", trainSpawn,
                "dungeontrain debug chatlogs train-spawn on",
                "dungeontrain debug chatlogs train-spawn off"
            ),
            new CommandMenuEntry.Toggle(
                "Collision", collision,
                "dungeontrain debug chatlogs collision on",
                "dungeontrain debug chatlogs collision off"
            ),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
