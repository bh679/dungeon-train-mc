package games.brennan.dungeontrain.client;

import net.minecraft.world.phys.Vec3;

/**
 * One shot of the death moment's camera sequence.
 *
 * @param kind         how the lens moves
 * @param target       what the lens looks at
 * @param offset       lens start relative to the target: along the train (+X forward), up, across.
 *                     For an orbit: x = radius, y = height above the subject
 * @param offsetEnd    lens end for a dolly, else ignored
 * @param sweepDegrees how far a world arc turns around the death spot over its hold (signed), else 0
 * @param holdTicks    how long the shot holds before the cut
 * @param label        short name for logs and the review overlay
 */
public record DeathShot(Kind kind, Target target, Vec3 offset, Vec3 offsetEnd, double sweepDegrees, int holdTicks, String label) {

    public enum Kind {
        /** The player's own view; no camera override. Only this shot carries the red wash and the death tilt. */
        POV,
        /** Lens rides the carriage (offset re-projected through its pose every frame). */
        TRAIN_LOCKED,
        /** Lens rides the carriage and eases from {@code offset} to {@code offsetEnd}, then holds in the world. */
        DOLLY,
        /**
         * As DOLLY, but the direction is chosen at show time: the lens pulls out from beside the body
         * whichever way is clear (above first). The phase-one safety net when neither the spot nor the
         * carriage shot could play.
         */
        BODY_OUT,
        /** Lens circles the target through the longest arc with a clear view of it. */
        ORBIT,
        /** As ORBIT, but part of the looping second phase (the random mob arc). */
        ORBIT_LOOP,
        /** Lens fixed in the world where the shot starts, aimed at the body as the carriage rolls away. */
        TRANSITION,
        /** Lens fixed in the world beside a feature of the train, tracking it as it rides past. */
        FEATURE_TRACK,
        /** Lens and aim both fixed in the world; the train passes through the frame. */
        WORLD,
        /** Lens slides at constant speed from {@code offset} to {@code offsetEnd} around the death spot, aimed at it. */
        WORLD_DOLLY,
        /** Lens sweeps an arc around the death spot at constant speed, starting from {@code offset}, aimed at it. */
        WORLD_ARC,
        /** Tunnel set: inside the body's carriage at the window, looking out across the track. */
        TUNNEL_WINDOW_OUT,
        /** Tunnel set: just above the carriage roof, looking forward along the train. */
        TUNNEL_ROOF,
        /** Tunnel set: behind the body's carriage, low, looking back along the train. */
        TUNNEL_COUPLING,
        /** Tunnel set: the body's own eyes, looking forward along the train. Always clear. */
        TUNNEL_EYES,
        /** Tunnel set: beside the train at track level, rising to roof height, looking down its side. */
        TUNNEL_RISE,
        /** Tunnel set: above the roof looking up the tracks, drifting slowly backwards. */
        TUNNEL_ABOVE_BACK,
        /**
         * Closes phase one while the loop is on hold: the lens stays where the last shot left it and
         * tilts up to the sky, then the screen fades into the perks menu's night blue.
         */
        SKY_TILT;

        /** Phase 2 shots that play only when none of the open-air train shots has a clear path. */
        public boolean isTunnel() {
            return this == TUNNEL_WINDOW_OUT || this == TUNNEL_ROOF || this == TUNNEL_COUPLING || this == TUNNEL_EYES
                || this == TUNNEL_RISE || this == TUNNEL_ABOVE_BACK;
        }

        /** Open-air shots of the place; the loop falls back to the tunnel set when none of these can play. */
        public boolean isOpenAir() {
            return this == WORLD || this == WORLD_DOLLY || this == WORLD_ARC;
        }

        /** Shots of the looping second phase: open air, tunnel, and the subject shots that play in both. */
        public boolean isLoop() {
            return isOpenAir() || isTunnel() || this == FEATURE_TRACK || this == ORBIT_LOOP;
        }
    }

    public enum Target {
        /** The dead player, wherever the carriage carries them. */
        BODY,
        /** Where the player died, fixed in the world. */
        DEATH_SPOT,
        /** The entity that killed them. */
        KILLER,
        /** The last creature they had to do with. */
        CONTACT,
        /** Any creature riding the train, drawn afresh each time the shot comes round. */
        RANDOM_MOB,
        /** A contents template or carriage group near the body (from the server's list). */
        FEATURE,
        /** The block that killed them (fire, magma, cactus, powder snow...), riding with the carriage if on one. */
        HAZARD
    }

    public static DeathShot pov(int holdTicks) {
        return new DeathShot(Kind.POV, Target.BODY, Vec3.ZERO, Vec3.ZERO, 0, holdTicks, "POV");
    }

    public static DeathShot trainLocked(double x, double y, double z, int holdTicks, String label) {
        return new DeathShot(Kind.TRAIN_LOCKED, Target.BODY, new Vec3(x, y, z), Vec3.ZERO, 0, holdTicks, label);
    }

    public static DeathShot dolly(Vec3 from, Vec3 to, int holdTicks, String label) {
        return new DeathShot(Kind.DOLLY, Target.BODY, from, to, 0, holdTicks, label);
    }

    public static DeathShot bodyOut(int holdTicks, String label) {
        return new DeathShot(Kind.BODY_OUT, Target.BODY, Vec3.ZERO, Vec3.ZERO, 0, holdTicks, label);
    }

    public static DeathShot orbit(Target target, double radius, double height, int holdTicks, String label) {
        return new DeathShot(Kind.ORBIT, target, new Vec3(radius, height, 0), Vec3.ZERO, 0, holdTicks, label);
    }

    public static DeathShot orbitLoop(Target target, double radius, double height, int holdTicks, String label) {
        return new DeathShot(Kind.ORBIT_LOOP, target, new Vec3(radius, height, 0), Vec3.ZERO, 0, holdTicks, label);
    }

    public static DeathShot transition(double x, double y, double z, int holdTicks, String label) {
        return new DeathShot(Kind.TRANSITION, Target.BODY, new Vec3(x, y, z), Vec3.ZERO, 0, holdTicks, label);
    }

    public static DeathShot feature(int holdTicks, String label) {
        return new DeathShot(Kind.FEATURE_TRACK, Target.FEATURE, Vec3.ZERO, Vec3.ZERO, 0, holdTicks, label);
    }

    public static DeathShot world(double x, double y, double z, int holdTicks, String label) {
        return new DeathShot(Kind.WORLD, Target.DEATH_SPOT, new Vec3(x, y, z), Vec3.ZERO, 0, holdTicks, label);
    }

    public static DeathShot worldDolly(Vec3 from, Vec3 to, int holdTicks, String label) {
        return new DeathShot(Kind.WORLD_DOLLY, Target.DEATH_SPOT, from, to, 0, holdTicks, label);
    }

    public static DeathShot worldArc(double x, double y, double z, double sweepDegrees, int holdTicks, String label) {
        return new DeathShot(Kind.WORLD_ARC, Target.DEATH_SPOT, new Vec3(x, y, z), Vec3.ZERO, sweepDegrees, holdTicks, label);
    }

    public static DeathShot skyTilt(int holdTicks) {
        return new DeathShot(Kind.SKY_TILT, Target.BODY, Vec3.ZERO, Vec3.ZERO, 0, holdTicks, "sky");
    }

    public static DeathShot tunnel(Kind kind, int holdTicks, String label) {
        return new DeathShot(kind, Target.BODY, Vec3.ZERO, Vec3.ZERO, 0, holdTicks, label);
    }
}
