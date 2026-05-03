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
 *   <li>{@link CarriageGroupGapDebugRenderer} — gates all world-space gap
 *       overlay rendering on {@link #wireframesEnabled()}.</li>
 *   <li>{@link VersionHudOverlay} — gates the second HUD line
 *       ({@code Δx to next group}) on {@link #wireframesEnabled()}.</li>
 *   <li>{@code DebugMenuScreen} — reads both flags to render Toggle button
 *       states.</li>
 * </ul>
 */
public final class DebugFlagsState {

    private static volatile boolean wireframesEnabled = false;
    private static volatile boolean manualSpawnMode = false;

    private DebugFlagsState() {}

    public static boolean wireframesEnabled() {
        return wireframesEnabled;
    }

    public static boolean manualSpawnMode() {
        return manualSpawnMode;
    }

    public static void applyServerState(DebugFlagsPacket packet) {
        wireframesEnabled = packet.wireframesEnabled();
        manualSpawnMode = packet.manualSpawnMode();
    }
}
