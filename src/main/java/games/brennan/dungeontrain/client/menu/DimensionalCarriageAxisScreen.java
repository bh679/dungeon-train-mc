package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Type one number of a dimensional carriage's setup — an axis of its box, or how far apart its extra
 * corridors go.
 *
 * <p>The arrows are for nudging; this is for getting somewhere. Walking a room from 11 to 48 wide is
 * thirty-seven taps, and the world-space panel has no typing of its own, so clicking the number
 * between the arrows opens this for that row alone.</p>
 *
 * <p>Prefilled with the current value, so the field doubles as a readout and nothing else on the
 * panel is touched. Values are clamped server-side: width and height cannot go under what the
 * corridor mouth needs to stay sealed, height cannot reach into the next portal pair's Y lane, and
 * the exits spacing cannot go tighter than a corridor is long.</p>
 */
public final class DimensionalCarriageAxisScreen implements MenuScreen {

    private final String axis;
    private final String label;
    private final String unit;
    private final int current;

    /**
     * @param axis    command token — {@code length}, {@code width} or {@code height}
     * @param label   display name for the field
     * @param current the axis's current value, used to prefill
     */
    public DimensionalCarriageAxisScreen(String axis, String label, int current) {
        this(axis, label, "blocks", current);
    }

    /**
     * @param axis    command token — the {@code /dt editor portals <axis> <n>} subcommand
     * @param label   display name for the field
     * @param unit    what the number counts, for the title and the typing hint
     * @param current the current value, used to prefill
     */
    public DimensionalCarriageAxisScreen(String axis, String label, String unit, int current) {
        this.axis = axis;
        this.label = label;
        this.unit = unit;
        this.current = current;
    }

    @Override public String title() {
        return label + " — " + unit;
    }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.TypeArg(
                label + " (" + current + ")",
                unit,
                "dungeontrain editor portals " + axis,
                "",
                Integer.toString(current)),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
