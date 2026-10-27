package games.brennan.dungeontrain.client;

import net.minecraft.world.phys.Vec3;

/**
 * One shot of the death moment's camera sequence.
 *
 * @param kind      how the lens moves
 * @param target    what the lens looks at
 * @param offset    lens start relative to the target: along the train (+X forward), up, across.
 *                  For an orbit: x = radius, y = height above the subject
 * @param offsetEnd lens end for a dolly, else ignored
 * @param sweepDegrees how far a world arc turns around the death spot over its hold (signed), else 0
 * @param holdTicks how long the shot holds before the cut
 * @param label     short name for logs and the review overlay
 */
public record DeathShot(Kind kind, Target target, Vec3 offset, Vec3 offsetEnd, double sweepDegrees, int holdTicks, String label) {

    public enum Kind {
        /** The player's own view; no camera override. Only this shot carries the red wash and the death tilt. */
        POV,
        /** Lens rides the carriage (offset re-projected through its pose every frame). */
        TRAIN_LOCKED,
        /** Lens rides the carriage and eases from {@code offset} to {@code offsetEnd}. */
        DOLLY,
        /** Lens circles the target through the longest arc with a clear view of it. */
        ORBIT,
        /** Lens fixed in the world where the shot starts, aimed at the body as the carriage rolls away. */
        TRANSITION,
        /** Lens and aim both fixed in the world; the train passes through the frame. */
        WORLD,
        /** Lens slides at constant speed from {@code offset} to {@code offsetEnd} around the death spot, aimed at it. */
        WORLD_DOLLY,
        /** Lens sweeps an arc around the death spot at constant speed, starting from {@code offset}, aimed at it. */
        WORLD_ARC
    }

    public enum Target {
        /** The dead player, wherever the carriage carries them. */
        BODY,
        /** Where the player died, fixed in the world. */
        DEATH_SPOT,
        /** The entity that killed them. */
        KILLER,
        /** The other mob they fought most recently. */
        CONTACT
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

    public static DeathShot orbit(Target target, double radius, double height, int holdTicks, String label) {
        return new DeathShot(Kind.ORBIT, target, new Vec3(radius, height, 0), Vec3.ZERO, 0, holdTicks, label);
    }

    public static DeathShot transition(double x, double y, double z, int holdTicks, String label) {
        return new DeathShot(Kind.TRANSITION, Target.BODY, new Vec3(x, y, z), Vec3.ZERO, 0, holdTicks, label);
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
}
