package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.net.PortalTrainAudioPacket;

/**
 * Client-side cache of the portal structure the player is in, and the engine volume it implies —
 * the audio counterpart to {@link games.brennan.dungeontrain.client.ClientPortalRoomFog}.
 *
 * <p><b>The rule.</b> A twin corridor is a copy of the carriage the player just walked out of, so it
 * sounds like one: full volume, the same figure {@link TrainEngineSound} gives for being inside a
 * sub-level, which is what makes the transit itself inaudible. Step out of the corridor mouth and the
 * train is behind you — the engine fades linearly over {@link PortalTrainAudioPacket#fadeBlocks}
 * blocks and is gone. Measured from the corridor box rather than from a threshold plane, so it is
 * symmetric: the entry and exit mouths behave the same, walking back toward one brings the sound
 * back, and a player wandering sideways in an endless room is as far from the train as the geometry
 * says they are.</p>
 *
 * <p><b>It stores a place, not a state</b> — see the packet's javadoc. Nothing has to arrive for the
 * rule to stop applying; stepping outside the structure's bounds is enough, which is what stops a
 * disconnect inside a corridor from stranding somebody with the engine pinned on.</p>
 *
 * <p>No easing, unlike the fog. The fog's thresholds move underneath a standing player as room copies
 * appear and retire; these do not — the corridors are fixed for as long as the structure stands, and
 * the only way across the fade band is to walk it, which already takes the best part of a second.</p>
 *
 * <p>Pure logic, no Minecraft imports, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class ClientPortalTrainAudio {

    /**
     * Returned when the player is not in a portal structure: "this rule has nothing to say, use the
     * ordinary distance curve". Distinct from {@code 0}, which is a real answer meaning silence.
     */
    public static final float NOT_APPLICABLE = -1.0f;

    /**
     * Containment slack, in blocks — the same figure {@code PortalGeometry} uses, so a player
     * clipping a corner or standing in a doorway still reads as inside the corridor rather than
     * flickering a fifth of a block into the fade.
     */
    private static final double PAD = 0.5;

    private static volatile PortalTrainAudioPacket region = PortalTrainAudioPacket.none();

    private ClientPortalTrainAudio() {}

    /** Apply a server update. */
    public static void update(PortalTrainAudioPacket packet) {
        region = packet == null ? PortalTrainAudioPacket.none() : packet;
    }

    /** Forget everything. Wired to logging out, so a structure never leaks into the next world. */
    public static void reset() {
        region = PortalTrainAudioPacket.none();
    }

    /**
     * The engine volume this position demands — {@code 1} inside a twin corridor, fading to {@code 0}
     * over the fade band beyond its mouth, {@code 0} anywhere else in the structure — or
     * {@link #NOT_APPLICABLE} when the player is not in one.
     */
    public static float volumeAt(double x, double y, double z) {
        PortalTrainAudioPacket r = region;
        if (!r.applies()) return NOT_APPLICABLE;
        if (!withinStructure(r, x, y, z)) return NOT_APPLICABLE;

        double distance = Math.min(
            distanceToCorridor(r, r.entryX(), r.entryY(), r.entryZ(), x, y, z),
            distanceToCorridor(r, r.exitX(), r.exitY(), r.exitZ(), x, y, z));
        if (distance <= 0.0) return 1.0f;
        if (distance >= r.fadeBlocks()) return 0.0f;
        return (float) (1.0 - distance / r.fadeBlocks());
    }

    /** True when the position is anywhere in the structure — corridors, room, and every copy of it. */
    private static boolean withinStructure(PortalTrainAudioPacket r, double x, double y, double z) {
        return x >= r.minX() && x <= r.maxX()
            && y >= r.minY() && y <= r.maxY()
            && z >= r.minZ() && z <= r.maxZ();
    }

    /**
     * Distance from the position to a corridor's box, {@code 0} inside it. The corridor spans
     * {@code length × height × width} from its minimum corner, padded by {@link #PAD}.
     */
    private static double distanceToCorridor(PortalTrainAudioPacket r, int ox, int oy, int oz,
                                             double x, double y, double z) {
        double dx = axisDistance(x, ox - PAD, ox + r.length() + PAD);
        double dy = axisDistance(y, oy - PAD, oy + r.height() + PAD);
        double dz = axisDistance(z, oz - PAD, oz + r.width() + PAD);
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) return 0.0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisDistance(double v, double min, double max) {
        if (v < min) return min - v;
        if (v > max) return v - max;
        return 0.0;
    }
}
