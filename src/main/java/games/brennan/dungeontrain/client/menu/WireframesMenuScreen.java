package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.DebugFlagsState;

import java.util.List;

/**
 * Drilled into from {@link DebugMenuScreen} as "Wireframes". Lists the
 * five wireframe overlays as individual {@link CommandMenuEntry.Toggle}
 * rows, with an "All On / All Off" {@link CommandMenuEntry.Split} master
 * at the top so the player can flip every overlay in one click.
 *
 * <p>The Split halves use {@link CommandMenuEntry.Stay} (not Run) so the
 * menu stays open after a master flip — the player immediately sees all
 * five Toggle rows update to {@code [ON]}/{@code [OFF]} on the next per-tick
 * rebuild. Toggle rows are kept full-width (not nested in a Split) so they
 * receive the green/grey row tint applied by {@code CommandMenuRenderer}
 * to top-level Toggle entries.</p>
 *
 * <p>Each Toggle reads its current state from {@link DebugFlagsState} —
 * the same client mirror the renderers gate on — so the displayed state
 * always matches what's actually being drawn.</p>
 */
public final class WireframesMenuScreen implements MenuScreen {

    @Override public String title() { return "Wireframes"; }

    @Override public List<CommandMenuEntry> entries() {
        boolean cubes = DebugFlagsState.gapCubes();
        boolean line = DebugFlagsState.gapLine();
        boolean nextSpawn = DebugFlagsState.nextSpawn();
        boolean collision = DebugFlagsState.collision();
        boolean hud = DebugFlagsState.hudDistance();

        return List.of(
            new CommandMenuEntry.Split(
                new CommandMenuEntry.Stay("All On", "dungeontrain debug wireframes all on"),
                new CommandMenuEntry.Stay("All Off", "dungeontrain debug wireframes all off"),
                0.50
            ),
            new CommandMenuEntry.Toggle(
                "Gap Cubes", cubes,
                "dungeontrain debug wireframes gap-cubes on",
                "dungeontrain debug wireframes gap-cubes off"
            ),
            new CommandMenuEntry.Toggle(
                "Gap Line", line,
                "dungeontrain debug wireframes gap-line on",
                "dungeontrain debug wireframes gap-line off"
            ),
            new CommandMenuEntry.Toggle(
                "Next Spawn", nextSpawn,
                "dungeontrain debug wireframes next-spawn on",
                "dungeontrain debug wireframes next-spawn off"
            ),
            new CommandMenuEntry.Toggle(
                "Collision", collision,
                "dungeontrain debug wireframes collision on",
                "dungeontrain debug wireframes collision off"
            ),
            new CommandMenuEntry.Toggle(
                "HUD Distance", hud,
                "dungeontrain debug wireframes hud-distance on",
                "dungeontrain debug wireframes hud-distance off"
            ),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
