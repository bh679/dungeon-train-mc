package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.DebugFlagsPacket;

/**
 * Client-side mirror of {@link games.brennan.dungeontrain.debug.DebugFlags}.
 * Updated by {@link DebugFlagsPacket#handle} on every server-side change
 * (and on player join, so the in-world menu's Toggle entries render the
 * correct state the first time they're shown).
 *
 * <p>Read by:
 * <ul>
 *   <li>{@link CarriageGroupGapDebugRenderer} — gates each world-space
 *       overlay section (gap cubes, precise-length line, next-spawn box,
 *       collision box) on its individual flag.</li>
 *   <li>{@link VersionHudOverlay} — gates the second HUD line
 *       ({@code Δx to next group}) on {@link #hudDistance()}.</li>
 *   <li>{@code WireframesMenuScreen} — reads all five flags to render the
 *       per-overlay Toggle button states.</li>
 *   <li>{@code DebugMenuScreen} — reads {@link #manualSpawnMode()} to
 *       render its Manual Spawn Toggle.</li>
 * </ul>
 */
public final class DebugFlagsState {

    private static volatile boolean gapCubes = false;
    private static volatile boolean gapLine = false;
    private static volatile boolean nextSpawn = false;
    private static volatile boolean collision = false;
    private static volatile boolean hudDistance = false;
    private static volatile boolean manualSpawnMode = false;

    private DebugFlagsState() {}

    public static boolean gapCubes() { return gapCubes; }
    public static boolean gapLine() { return gapLine; }
    public static boolean nextSpawn() { return nextSpawn; }
    public static boolean collision() { return collision; }
    public static boolean hudDistance() { return hudDistance; }
    public static boolean manualSpawnMode() { return manualSpawnMode; }

    public static void applyServerState(DebugFlagsPacket packet) {
        gapCubes = packet.gapCubes();
        gapLine = packet.gapLine();
        nextSpawn = packet.nextSpawn();
        collision = packet.collision();
        hudDistance = packet.hudDistance();
        manualSpawnMode = packet.manualSpawnMode();
    }
}
