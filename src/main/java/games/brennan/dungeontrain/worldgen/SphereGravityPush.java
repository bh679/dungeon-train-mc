package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.SpheresGravityConfig;
import games.brennan.dungeontrain.util.ProjectilePush;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Applies {@link SphereGravity} to one entity: the summed pull of the spheres near it, scaled by what it
 * is and where it stands, added to its velocity. Shared by {@code SphereGravityServer} (everything but
 * players) and {@code client.SphereGravityClient} (the local player — player movement is
 * client-authoritative).
 *
 * <p>Sable carries riders by position plus an inherited velocity that never shows up in
 * {@code deltaMovement}, so this additive push stacks on the carriage's motion rather than fighting it.</p>
 */
public final class SphereGravityPush {

    /** Projectiles bend toward a sphere but keep flying — half the pull. */
    static final double PROJECTILE_FACTOR = 0.5;

    private SphereGravityPush() {}

    /** The pull {@code e} feels from {@code spheres}, before the train-shelter factor. */
    public static SphereGravity.Pull pullOn(Entity e, List<SphereField.Sphere> spheres) {
        if (spheres.isEmpty()) return SphereGravity.Pull.ZERO;
        Vec3 v = e.getDeltaMovement();
        double midY = e.getY() + e.getBbHeight() * 0.5;
        SphereGravity.Pull p = SphereGravity.pull(spheres, e.getX(), midY, e.getZ(), v.x, v.y, v.z, held(e),
                SpheresGravityConfig.strength(), SpheresGravityConfig.reachRadii(), SpheresGravityConfig.hold());
        return p.isZero() ? p : p.scale(entityFactor(e));
    }

    /** Standing or swimming: on the ground, or in water or lava. A sphere lets go of what it holds. */
    public static boolean held(Entity e) {
        return e.onGround() || e.isInWater() || e.isInLava();
    }

    /** What the entity is: knockback resistance shrugs some of the pull off; projectiles take half. */
    static double entityFactor(Entity e) {
        if (e instanceof Projectile) return PROJECTILE_FACTOR;
        if (e instanceof LivingEntity living) {
            double kr = living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            return 1.0 - Math.max(0.0, Math.min(1.0, kr));
        }
        return 1.0;
    }

    /**
     * Add {@code pull × factor} to the entity's velocity. No-op for a zero pull or factor, and for a projectile
     * stuck in a block; a pulled projectile has its new velocity sent to clients this tick, the same two steps
     * the train wind takes ({@link ProjectilePush}) so an arrow's curve toward a sphere reads smoothly.
     */
    public static void apply(Entity e, SphereGravity.Pull pull, double factor) {
        if (pull.isZero() || factor <= 0.0 || ProjectilePush.stuck(e)) return;
        e.setDeltaMovement(e.getDeltaMovement().add(pull.x() * factor, pull.y() * factor, pull.z() * factor));
        ProjectilePush.markPushed(e);
    }

    /** Lookup reach for the current config: the largest sphere's pull, past its surface. */
    public static double lookupReach() {
        return SphereGravity.lookupReach(games.brennan.dungeontrain.config.DungeonTrainCommonConfig.getSpheresMaxRadius(),
                SpheresGravityConfig.reachRadii());
    }
}
