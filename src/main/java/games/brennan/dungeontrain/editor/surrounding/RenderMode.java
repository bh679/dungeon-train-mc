package games.brennan.dungeontrain.editor.surrounding;

/**
 * How a {@link SurroundingStructureCategory} is shown around the plot a player is currently editing.
 *
 * <ul>
 *   <li>{@link #GHOST} — translucent, client-only preview blocks. No collision, no shared state;
 *       resolved per-player and pushed over the network (see {@code SurroundingStructureGhostPacket}).</li>
 *   <li>{@link #SOLID} — real stamped blocks with collision. Since these are actual world blocks,
 *       {@link SurroundingStructureManager} tracks them per-plot (not per-player) — see its class
 *       doc for the last-write-wins design note.</li>
 *   <li>{@link #NONE} — nothing rendered or stamped for that category.</li>
 * </ul>
 */
public enum RenderMode {
    GHOST,
    SOLID,
    NONE
}
