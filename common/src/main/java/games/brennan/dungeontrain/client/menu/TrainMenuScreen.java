package games.brennan.dungeontrain.client.menu;

import java.util.List;

/** Spawn a train / drill into the speed or carriage-count presets. */
public final class TrainMenuScreen implements MenuScreen {

    @Override public String title() { return "Train"; }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run("Spawn", "dungeontrain spawn"),
            new CommandMenuEntry.DrillIn("Carriages", new CarriagesMenuScreen()),
            new CommandMenuEntry.DrillIn("Speed", new SpeedMenuScreen()),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
