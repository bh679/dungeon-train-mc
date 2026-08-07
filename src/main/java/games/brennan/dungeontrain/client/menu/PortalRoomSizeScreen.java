package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Type a portal room's whole box in one field: {@code length width height}.
 *
 * <p>The steppers are for nudging; this is for getting somewhere. Walking a room from 13 to 48 wide
 * is thirty-five taps, and the world-space panel has no typing of its own — its number cells are
 * display-only — so clicking one opens this instead.</p>
 *
 * <p>Prefilled with the current size, so the field doubles as a readout of all three axes at once
 * and editing one number leaves the others alone. Values are clamped server-side: width and height
 * cannot go under what the corridor mouth needs to stay sealed, and height cannot reach into the
 * next portal pair's Y lane.</p>
 */
public final class PortalRoomSizeScreen implements MenuScreen {

    private final int length;
    private final int width;
    private final int height;

    public PortalRoomSizeScreen(int length, int width, int height) {
        this.length = length;
        this.width = width;
        this.height = height;
    }

    @Override public String title() {
        return "Size — length width height";
    }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.TypeArg(
                "Size (" + length + " " + width + " " + height + ")",
                "l w h",
                "dungeontrain editor portals size",
                "",
                length + " " + width + " " + height),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
