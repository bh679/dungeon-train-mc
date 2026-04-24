package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Preset speed choices. Range is {@code [0.0, 20.0]} per
 * {@code DungeonTrainConfig.MIN_SPEED}/{@code MAX_SPEED}; presets chosen
 * to cover a useful spread for iterating on train physics.
 */
public final class SpeedMenuScreen implements MenuScreen {

    @Override public String title() { return "Speed"; }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run("Stop (0)", "dungeontrain speed 0"),
            new CommandMenuEntry.Run("Slow (2)", "dungeontrain speed 2"),
            new CommandMenuEntry.Run("Medium (5)", "dungeontrain speed 5"),
            new CommandMenuEntry.Run("Fast (10)", "dungeontrain speed 10"),
            new CommandMenuEntry.Run("Max (20)", "dungeontrain speed 20"),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
