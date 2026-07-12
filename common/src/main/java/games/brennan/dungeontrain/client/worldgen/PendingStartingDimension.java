package games.brennan.dungeontrain.client.worldgen;

import games.brennan.dungeontrain.world.StartingDimension;

/**
 * Client-side static holder for the starting dimension implied by the World
 * Type preset selected on {@code CreateWorldScreen}. Updated each tick from
 * {@code CreateWorldScreenMixin} (mirrors {@link FloorYState} for the Floor-Y
 * dropdown), read once on integrated-server start by
 * {@code WorldLifecycleEvents}, then cleared.
 *
 * Defaults to {@link StartingDimension#OVERWORLD} so a user who picks a vanilla
 * preset, never opens a Dungeon Train screen, or runs on a dedicated server,
 * commits as overworld — preserving the legacy behaviour.
 *
 * Client-only — never referenced from a class loaded on a dedicated server.
 */
public final class PendingStartingDimension {

    private static volatile StartingDimension value = StartingDimension.OVERWORLD;

    private PendingStartingDimension() {}

    public static StartingDimension get() {
        return value;
    }

    public static void set(StartingDimension d) {
        value = d == null ? StartingDimension.OVERWORLD : d;
    }

    public static void clear() {
        value = StartingDimension.OVERWORLD;
    }
}
