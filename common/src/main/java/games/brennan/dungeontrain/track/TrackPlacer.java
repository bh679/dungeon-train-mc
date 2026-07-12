package games.brennan.dungeontrain.track;

/**
 * Fixed shape constants for the open-air track tile authored via
 * {@link games.brennan.dungeontrain.editor.TrackEditor} and stamped by
 * {@link TrackGenerator}. Unlike {@link games.brennan.dungeontrain.tunnel.TunnelPlacer}
 * this is a repeating tile, not a one-shot placement — the template is
 * {@link #TILE_LENGTH} blocks wide on X and re-stamped every
 * {@code worldX mod TILE_LENGTH}.
 *
 * <p>Rows:</p>
 * <ul>
 *   <li>{@code y = 0} — the bed row ({@code worldY = bedY})</li>
 *   <li>{@code y = 1} — the rail row ({@code worldY = railY = bedY + 1})</li>
 * </ul>
 *
 * <p>Z extent equals the runtime {@code CarriageDims.width()} — same convention
 * as the pillar template, and matches the track span
 * {@code [trackZMin .. trackZMax]}.</p>
 */
public final class TrackPlacer {

    /** Number of blocks on X before the tile repeats. Fixed at 4 per spec. */
    public static final int TILE_LENGTH = 4;

    /** Row count — bed row + rail row. */
    public static final int HEIGHT = 2;

    private TrackPlacer() {}
}
