package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Type one axis of a portal room's box — length, width or height, each on its own.
 *
 * <p>The arrows are for nudging; this is for getting somewhere. Walking a room from 13 to 48 wide is
 * thirty-five taps, and the world-space panel has no typing of its own, so clicking the number
 * between the arrows opens this for that row's axis alone.</p>
 *
 * <p>Prefilled with the current value, so the field doubles as a readout and the other two axes are
 * never touched. Values are clamped server-side: width and height cannot go under what the corridor
 * mouth needs to stay sealed, and height cannot reach into the next portal pair's Y lane.</p>
 */
public final class PortalRoomAxisScreen implements MenuScreen {

    private final String axis;
    private final String label;
    private final int current;

    /**
     * @param axis    command token — {@code length}, {@code width} or {@code height}
     * @param label   display name for the field
     * @param current the axis's current value, used to prefill
     */
    public PortalRoomAxisScreen(String axis, String label, int current) {
        this.axis = axis;
        this.label = label;
        this.current = current;
    }

    @Override public String title() {
        return label + " — blocks";
    }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.TypeArg(
                label + " (" + current + ")",
                "blocks",
                "dungeontrain editor portals " + axis,
                "",
                Integer.toString(current)),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
