package games.brennan.dungeontrain.portal;

import java.util.List;

/**
 * Where black smoke seeps out of a corridor's <b>portal-ward</b> door, in carriage-local
 * coordinates — the closed door at the end that leads on to the pocket room.
 *
 * <p><b>One source, two copies.</b> A corridor exists twice: the one riding the train and its
 * static twin. Which one a player is physically in is decided by which side of the midpoint they
 * stand on, and the whole illusion rests on the two being indistinguishable. Smoke in one copy and
 * not the other would give the crossing away outright, and smoke that drifted differently in each
 * would do it more slowly. So this answers in <i>local</i> coordinates, once, and
 * {@link PortalDoorSmokeEmitter} plays the same answer into both frames — the same trick
 * {@link PortalCarriageBuilder#stateAt} uses to keep the blocks identical by construction rather
 * than by review.</p>
 *
 * <p><b>Which door.</b> The portal-ward one is the door on the twin side of the midpoint — the same
 * side rule {@link PortalFrames#requiredMove} swaps on, so it follows the role: the far door for an
 * {@link PortalCarriageRole#ENTRY} corridor, the near door for an {@link PortalCarriageRole#EXIT}
 * one. In the carriage copy that door is the dummy with a plug behind it; in the twin it is the real
 * one that opens on the room. Both are stamped closed, and both smoke.</p>
 *
 * <p><b>Seeping, not billowing.</b> One particle every {@link #EMIT_INTERVAL_TICKS} ticks, cycling
 * around three points on the frame, drifting into the corridor at a fraction of a block per second.
 * It is atmosphere — a hint that the far side is not empty — and it must not fog up the doorway the
 * player is walking through.</p>
 *
 * <p><b>Out of the door, not up from it.</b> The drift is horizontal, and the particle it feeds is
 * chosen for zero gravity ({@link PortalDoorSmokeEmitter}), so the smoke crawls out into the corridor
 * and settles instead of climbing. Rising smoke reads as a fire behind the door; this should read as
 * something heavy leaking past it.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 *
 * @param layout the corridor layout both copies were stamped from
 * @param role   which end of this corridor faces the room
 */
public record PortalDoorSmoke(PortalCarriageLayout layout, PortalCarriageRole role) {

    /** Ticks between emissions. Every other tick is a thin, continuous seep rather than a puff. */
    public static final int EMIT_INTERVAL_TICKS = 2;

    /** How many points around the frame the seep cycles through. */
    private static final int POINTS = 3;

    /**
     * How far past the door plane's centre a particle starts, in blocks.
     *
     * <p>Over half a block, so it lands just <i>outside</i> the door's own cell. Smoke has physics:
     * spawned inside the door block it would be shoved back out on its first tick, which reads as a
     * flick rather than a seep.</p>
     */
    private static final double FACE_OFFSET = 0.55;

    /** Height above the floor of the seep under the door — low enough to look like it is creeping out. */
    private static final double UNDER_DOOR_Y = 1.06;

    /** Height of the two seeps at the sides of the frame — low, around the handle rather than the lintel. */
    private static final double FRAME_Y = 1.5;

    /** How far off the doorway's centre line the frame seeps sit. Inside a one-block doorway. */
    private static final double FRAME_Z = 0.34;

    /**
     * Drift into the corridor, in blocks per tick, at the moment of release.
     *
     * <p>It decays: the particle this feeds carries friction, so the seep slows as it goes and comes
     * to rest something like a block out from the door. That deceleration is most of what makes it
     * look like smoke leaking under a door rather than being blown out of one.</p>
     */
    private static final double DRIFT = 0.04;

    /**
     * A trace of sink, so the smoke settles toward the floor as it slows.
     *
     * <p>Deliberately not lift. Smoke that climbs reads as a fire on the other side of the door;
     * smoke that spreads and lies low reads as something heavier getting past it, which is the
     * impression the portal wants.</p>
     */
    private static final double SINK = -0.004;

    /** Sideways spill, which keeps the three seeps from reading as one straight line. */
    private static final double SPILL = 0.006;

    /**
     * Ticks between haze quads.
     *
     * <p>Set against the haze's own lifetime rather than picked for a rate: at roughly 90 ticks a
     * quad, one every fifteen keeps about six of them alive in the doorway at once, which is the
     * overlap that makes a stack of flat sprites read as a volume. Slower and the frame flickers
     * between thick and thin as they expire.</p>
     */
    private static final int HAZE_INTERVAL_TICKS = 15;

    /** Height of the haze — the middle of the two-block doorway opening. */
    private static final double HAZE_Y = 1.9;

    /** How far the haze alternates either side of the doorway centre, so the stack has depth. */
    private static final double HAZE_Z = 0.18;

    /** Barely a drift. Enough that the haze breathes outward rather than hanging perfectly still. */
    private static final double HAZE_DRIFT = 0.004;

    /** And barely a sink, for the same reason. */
    private static final double HAZE_SINK = -0.001;

    /**
     * Which of the two looks a particle is. The emitter maps these onto the two registered types;
     * the sizes and lifetimes themselves live client-side in {@code PortalSmokeParticle}, since
     * nothing here needs to know them.
     */
    public enum Kind {
        /** Small and fairly solid — what peels off the frame and drifts down the corridor. */
        WISP,
        /** Big, faint and near-still — the body of it, sitting in the doorway. */
        HAZE
    }

    /**
     * One particle: a corridor-local position and the velocity it leaves with, both in blocks
     * (velocity per tick).
     */
    public record Emission(Kind kind, double x, double y, double z, double vx, double vy, double vz) {}

    /** Local X of the door plane facing the room. */
    public int doorX() {
        return role == PortalCarriageRole.ENTRY ? layout.farDoorX() : layout.nearDoorX();
    }

    /**
     * Which way the corridor lies from that door: {@code -1} for an ENTRY corridor's far door,
     * {@code +1} for an EXIT corridor's near door. Both the offset and the drift use it, so the
     * smoke can never be emitted into the wall behind the door.
     */
    public int intoCorridor() {
        return role == PortalCarriageRole.ENTRY ? -1 : 1;
    }

    /** True on the ticks a wisp comes off the frame. */
    public boolean emitsOn(long gameTime) {
        return Math.floorMod(gameTime, EMIT_INTERVAL_TICKS) == 0;
    }

    /**
     * Everything to spawn on this tick — a wisp every {@link #EMIT_INTERVAL_TICKS}, a haze quad
     * every {@link #HAZE_INTERVAL_TICKS}, both or neither. Empty on the ticks between.
     *
     * <p>A pure function of the tick, which is what lets the two copies be fed from one call without
     * either of them holding state that could drift out of step with the other.</p>
     */
    public List<Emission> emissionsOn(long gameTime) {
        Emission wisp = wispOn(gameTime);
        Emission haze = hazeOn(gameTime);
        if (wisp == null && haze == null) return List.of();
        if (wisp == null) return List.of(haze);
        if (haze == null) return List.of(wisp);
        return List.of(wisp, haze);
    }

    /** The wisp for this tick, or {@code null}. */
    public Emission wispOn(long gameTime) {
        if (!emitsOn(gameTime)) return null;

        int into = intoCorridor();
        double zCentre = layout.doorZ() + 0.5;
        int point = (int) Math.floorMod(Math.floorDiv(gameTime, EMIT_INTERVAL_TICKS), (long) POINTS);

        // Point 0 is the seep under the door, which is the one that sells it; the other two come out
        // at the sides of the frame higher up.
        double y = point == 0 ? layout.floorY() + UNDER_DOOR_Y : layout.floorY() + FRAME_Y;
        double side = point == 1 ? -1.0 : point == 2 ? 1.0 : 0.0;

        return new Emission(Kind.WISP,
            faceX(), y, zCentre + side * FRAME_Z,
            into * DRIFT, SINK, side * SPILL);
    }

    /**
     * The haze quad for this tick, or {@code null}.
     *
     * <p>Sits at the middle of the doorway opening and barely moves — the volume is an illusion of
     * several long-lived translucent quads overlapping in the frame, so what matters is that they
     * stay there, not that they travel. It alternates a little either side of the centre line so the
     * stack does not collapse into one plane.</p>
     */
    public Emission hazeOn(long gameTime) {
        if (Math.floorMod(gameTime, HAZE_INTERVAL_TICKS) != 0) return null;

        int into = intoCorridor();
        double side = Math.floorMod(Math.floorDiv(gameTime, HAZE_INTERVAL_TICKS), 2L) == 0L ? -1.0 : 1.0;

        return new Emission(Kind.HAZE,
            faceX(), layout.floorY() + HAZE_Y, layout.doorZ() + 0.5 + side * HAZE_Z,
            into * HAZE_DRIFT, HAZE_SINK, 0.0);
    }

    /**
     * Local X every particle starts at: clear of the door's own cell, on the corridor side.
     *
     * <p>Shared by both kinds because the reason is the same for both — a particle spawned inside
     * the door block is shoved out of it on its first tick, which reads as a flick rather than a
     * leak.</p>
     */
    private double faceX() {
        return doorX() + 0.5 + intoCorridor() * FACE_OFFSET;
    }
}
