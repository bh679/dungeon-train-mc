package games.brennan.dungeontrain.net;

/**
 * A ride-photo category the <em>server</em> can ask a client to capture, sent as
 * {@link SnapshotCuePacket}.
 *
 * <p>Deliberately a separate enum from {@code client.snapshot.SnapshotTag}: the tag lives in a
 * client-only package, and the code that detects these moments (the drifting-carriage registry,
 * the portal-room dwell tracker) runs on the server, including a dedicated one where those client
 * classes are absent. The mapping back to a tag happens inside the packet's client handler.</p>
 *
 * <p>Only moments the server alone can see belong here. Everything the client can detect for
 * itself — combat, gear, books, player-mobs — stays in {@code RideSnapshotDirector}.</p>
 */
public enum SnapshotCue {
    /** The player changed a drifting carriage (placed/broke a block in one, or left a gift). */
    BUILDING,
    /** The player has been inside a Train Dimension's room body long enough to have arrived. */
    THRESHOLD
}
