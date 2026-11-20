package games.brennan.dungeontrain.ship.sable;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * How hard the death body ({@link DeathBodyRagdoll}) is thrown by what killed the player: along the
 * shot for a projectile, away from the centre for a blast. Pure maths, no world access; every result
 * is in blocks per second (the velocity change {@code RagdollBridge.throwBody} gives every part of
 * the body) and capped, so a body never
 * leaves the loaded area or picks up enough speed to tunnel through a carriage wall.
 */
public final class DeathKnockback {

    /** Blocks per second of throw per block per tick of projectile speed (a full bow shot, ~3 b/t, hits the cap). */
    static final double PROJECTILE_SCALE = 4.0;
    /** Fastest a projectile throws a body (blocks per second). */
    static final double PROJECTILE_MAX = 12.0;
    /** Upward lift on a projectile throw, so the body lifts off instead of skidding (blocks per second). */
    static final double PROJECTILE_LIFT = 2.0;

    /** Throw at the very centre of a radius-{@link #REFERENCE_RADIUS} blast (blocks per second). */
    static final double BLAST_SPEED = 14.0;
    /** Fastest a blast throws a body (blocks per second). */
    static final double BLAST_MAX = 16.0;
    /** Upward lift on a blast throw, at full strength (blocks per second). */
    static final double BLAST_LIFT = 3.0;
    /** A TNT blast's radius; power scales relative to it. */
    static final double REFERENCE_RADIUS = 4.0;
    /** Power scaling stays within these bounds, however small or huge the blast. */
    static final double MIN_POWER_FACTOR = 0.5;
    static final double MAX_POWER_FACTOR = 1.5;
    /** Radius assumed when the blast itself was not seen (a modded explosion with no event). */
    public static final float DEFAULT_RADIUS = 4.0f;

    /**
     * Fastest a body riding a carriage is thrown across the track (blocks per second). Along the
     * track the carriage's walls and ends catch it; across an open flatbed nothing does, and a body
     * thrown off the side is left behind by the train, out of the death camera's reach.
     */
    static final double ON_TRAIN_ACROSS_MAX = 1.5;

    private static final double EPSILON = 1.0e-6;

    private DeathKnockback() {}

    /**
     * The throw from a projectile moving at {@code velocityPerTick} (blocks per tick) as it hit, or
     * from {@code fallbackFrom} toward {@code body} when the projectile had stopped. Zero when neither
     * gives a direction.
     */
    public static Vec3 projectile(@Nullable Vec3 velocityPerTick, @Nullable Vec3 fallbackFrom, Vec3 body) {
        Vec3 dir;
        double speed;
        if (velocityPerTick != null && velocityPerTick.lengthSqr() > EPSILON) {
            speed = velocityPerTick.length() * PROJECTILE_SCALE;
            dir = velocityPerTick.normalize();
        } else if (fallbackFrom != null && body.subtract(fallbackFrom).lengthSqr() > EPSILON) {
            speed = PROJECTILE_MAX * 0.5;
            dir = body.subtract(fallbackFrom).normalize();
        } else {
            return Vec3.ZERO;
        }
        double clamped = Math.min(speed, PROJECTILE_MAX);
        return cap(dir.scale(clamped).add(0, PROJECTILE_LIFT * clamped / PROJECTILE_MAX, 0), PROJECTILE_MAX);
    }

    /**
     * The throw from a blast of {@code radius} centred at {@code centre} on a body at {@code body}.
     * Falls off with distance as vanilla's explosion knockback does ({@code 1 - d / 2r}), grows with
     * the blast's power, and points straight up when the body sat at the centre. Zero out of reach.
     */
    public static Vec3 explosion(Vec3 centre, Vec3 body, float radius) {
        if (!(radius > 0)) return Vec3.ZERO;
        Vec3 offset = body.subtract(centre);
        double distance = offset.length();
        double impact = 1.0 - distance / (2.0 * radius);
        if (impact <= 0) return Vec3.ZERO;
        Vec3 dir = distance > EPSILON ? offset.scale(1.0 / distance) : new Vec3(0, 1, 0);
        double speed = Math.min(BLAST_SPEED * impact * powerFactor(radius), BLAST_MAX);
        return cap(dir.scale(speed).add(0, BLAST_LIFT * impact, 0), BLAST_MAX);
    }

    /**
     * {@code thrown} with its part across the track (horizontal, square to {@code trackAxis}) cut to
     * {@link #ON_TRAIN_ACROSS_MAX}; the part along the track and the lift are kept. A zero or vertical
     * axis leaves the throw as it is.
     */
    public static Vec3 keepAboard(Vec3 thrown, Vec3 trackAxis) {
        Vec3 axis = new Vec3(trackAxis.x, 0, trackAxis.z);
        if (axis.lengthSqr() < EPSILON) return thrown;
        axis = axis.normalize();
        Vec3 horizontal = new Vec3(thrown.x, 0, thrown.z);
        Vec3 along = axis.scale(horizontal.dot(axis));
        Vec3 across = horizontal.subtract(along);
        double acrossSpeed = across.length();
        if (acrossSpeed <= ON_TRAIN_ACROSS_MAX) return thrown;
        Vec3 cut = across.scale(ON_TRAIN_ACROSS_MAX / acrossSpeed);
        return new Vec3(along.x + cut.x, thrown.y, along.z + cut.z);
    }

    /** {@code v} shortened to at most {@code max} long, lift included. */
    static Vec3 cap(Vec3 v, double max) {
        double length = v.length();
        return length > max ? v.scale(max / length) : v;
    }

    /** How much harder than TNT a blast of {@code radius} throws: the square root of the ratio, bounded. */
    static double powerFactor(float radius) {
        double factor = Math.sqrt(radius / REFERENCE_RADIUS);
        return Math.max(MIN_POWER_FACTOR, Math.min(MAX_POWER_FACTOR, factor));
    }
}
