package games.brennan.dungeontrain.client;

/**
 * Client-side cache of how far the train's Sable plot sat from its real X and Z when it spawned, as
 * the server recorded it ({@code PlotReadoutOffsetPacket}). Readouts of a targeted carriage block take
 * it off ({@link CoordinateDisguise#targetedBlock}). Unknown until the server says otherwise, and then
 * {@link MapReadoutText#PLOT_OFFSET} stands in. Dropped on disconnect so one world's train never
 * offsets the next.
 */
public final class ClientPlotReadoutOffset {

    private static volatile boolean known;
    private static volatile long dx;
    private static volatile long dz;

    private ClientPlotReadoutOffset() {}

    /** Apply a server update; {@code known} false means the world has no recorded offset. */
    public static void update(boolean isKnown, long newDx, long newDz) {
        dx = newDx;
        dz = newDz;
        known = isKnown;
    }

    /** Forget it. Wired to logging out. */
    public static void reset() {
        known = false;
        dx = 0L;
        dz = 0L;
    }

    /** X to take off a carriage block's plot X. */
    public static long dx() {
        return known ? dx : MapReadoutText.PLOT_OFFSET;
    }

    /** Z to take off a carriage block's plot Z. */
    public static long dz() {
        return known ? dz : MapReadoutText.PLOT_OFFSET;
    }
}
