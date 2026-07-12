package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Preset carriage-count choices. Range is {@code [1, 50]} per
 * {@code DungeonTrainConfig.MIN_CARRIAGES}/{@code MAX_CARRIAGES}; presets
 * chosen to cover a useful spread for iterating on train length. Each
 * choice persists to config and live-updates any active train (rolling
 * window grows or shrinks on the next tick).
 */
public final class CarriagesMenuScreen implements MenuScreen {

    @Override public String title() { return "Carriages"; }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run("1 carriage", "dungeontrain carriages 1"),
            new CommandMenuEntry.Run("3 carriages", "dungeontrain carriages 3"),
            new CommandMenuEntry.Run("5 carriages", "dungeontrain carriages 5"),
            new CommandMenuEntry.Run("10 carriages", "dungeontrain carriages 10"),
            new CommandMenuEntry.Run("20 carriages", "dungeontrain carriages 20"),
            new CommandMenuEntry.Run("50 carriages", "dungeontrain carriages 50"),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
