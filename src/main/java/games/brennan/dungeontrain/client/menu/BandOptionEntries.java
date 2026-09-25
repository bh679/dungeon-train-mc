package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.plot.LapBandCells;
import games.brennan.dungeontrain.worldgen.BandOption;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Keyboard-menu rows for a band gate: a label per {@link BandOption.Group} and a Toggle per option,
 * matching the world-space option row ({@link LapBandCells}). Click toggles the option, shift-click
 * its whole group; each sends the whole new mask through {@code maskCommand}. A change that would
 * leave no band re-sends the current mask (a no-op), since a Toggle always runs a command.
 */
public final class BandOptionEntries {

    private BandOptionEntries() {}

    public static List<CommandMenuEntry> of(int mask, IntFunction<String> maskCommand) {
        List<CommandMenuEntry> out = new ArrayList<>();
        for (BandOption.Group group : BandOption.Group.values()) {
            out.add(new CommandMenuEntry.Label(BandLabels.group(group)));
            for (BandOption option : group.options()) {
                String toggle = maskCommand.apply(LapBandCells.toggle(mask, option.mask()).orElse(mask));
                String whole = maskCommand.apply(LapBandCells.toggle(mask, group.mask()).orElse(mask));
                boolean on = option.state(mask) == BandOption.State.ALL;
                out.add(new CommandMenuEntry.Toggle(BandLabels.option(option), on, toggle, toggle, true, whole));
            }
        }
        return out;
    }
}
