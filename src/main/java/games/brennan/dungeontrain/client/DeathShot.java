package games.brennan.dungeontrain.client;

import net.minecraft.world.phys.Vec3;

/**
 * One shot of the death moment's camera sequence.
 *
 * @param kind      how the lens and aim move
 * @param offset    where the lens starts, relative to the body: along the train (+X forward), up, across
 * @param holdTicks how long the shot holds before the cut
 * @param label     short name for logs and the review overlay
 */
public record DeathShot(Kind kind, Vec3 offset, int holdTicks, String label) {

    public enum Kind {
        /** The player's own view; no camera override. Only this shot carries the red wash. */
        POV,
        /** Lens rides the carriage (offset re-projected through its pose every frame), aimed at the body. */
        TRAIN_LOCKED,
        /** Lens fixed in the world where the shot starts, aimed at the body as the carriage rolls away. */
        TRANSITION,
        /** Lens and aim both fixed in the world; the train passes through the frame. */
        WORLD
    }

    public static DeathShot pov(int holdTicks) {
        return new DeathShot(Kind.POV, Vec3.ZERO, holdTicks, "POV");
    }

    public static DeathShot trainLocked(double x, double y, double z, int holdTicks, String label) {
        return new DeathShot(Kind.TRAIN_LOCKED, new Vec3(x, y, z), holdTicks, label);
    }

    public static DeathShot transition(double x, double y, double z, int holdTicks, String label) {
        return new DeathShot(Kind.TRANSITION, new Vec3(x, y, z), holdTicks, label);
    }

    public static DeathShot world(double x, double y, double z, int holdTicks, String label) {
        return new DeathShot(Kind.WORLD, new Vec3(x, y, z), holdTicks, label);
    }
}
