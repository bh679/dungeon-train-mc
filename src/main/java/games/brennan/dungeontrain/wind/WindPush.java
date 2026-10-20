package games.brennan.dungeontrain.wind;

import games.brennan.dungeontrain.util.ProjectilePush;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Applies one tick of wind to an entity: {@code deltaMovement += heading × strength × multiplier}, scaled down
 * by knockback resistance for living things. Horizontal only — the wind shoves, it doesn't lift.
 *
 * <p>Additive on {@code deltaMovement}, which Sable leaves to the entity: a rider is carried positionally plus
 * an inherited velocity that never appears in {@code deltaMovement} (see {@code SableEntityCarry}), so the wind
 * stacks on top of the carriage's motion instead of fighting it.</p>
 */
public final class WindPush {

    /**
     * Downwind speed (blocks/tick) past which the wind stops adding. Without a cap an entity that isn't moving
     * (a frozen or non-ticking one) would bank velocity every tick and be launched when it next moves.
     */
    static final double MAX_DOWNWIND_SPEED = 0.6;
    /**
     * Downwind speed band below {@link #MAX_DOWNWIND_SPEED} over which the push eases to nothing, instead of
     * switching off at the cap (a hard cut made an arrow's drift stutter as its speed crossed it).
     */
    static final double TAPER = 0.25;

    private WindPush() {}

    /** The profile multiplier for {@code e}'s category. */
    public static double multiplier(WindProfile.Multipliers m, Entity e) {
        if (e instanceof Player) return m.player();
        if (e instanceof ItemEntity) return m.item();
        if (e instanceof ExperienceOrb) return m.xp();
        if (e instanceof Projectile) return m.projectile();
        if (e instanceof LivingEntity) return m.mob();
        return m.other();
    }

    /** An entity's stand-in for mass: its bounding-box volume. */
    public static double mass(Entity e) {
        double w = e.getBbWidth();
        return w * w * e.getBbHeight();
    }

    /**
     * The push {@code e} would get from {@code w} this tick (blocks/tick², 0 for none):
     * <ol>
     *   <li>the wind ({@code extra} on top, e.g. the sneak factor; {@code airFactor} for a living thing off the
     *       ground),</li>
     *   <li>less the grip a grounded thing has for its weight ({@link WindProfile.Weight#threshold}) — the
     *       wind has to beat that before it moves anything,</li>
     *   <li>times the category multiplier, the light-things boost ({@link WindProfile.Weight#boost}) and
     *       (1 − knockback resistance).</li>
     * </ol>
     */
    public static double accel(Entity e, WindSample w, WindProfile profile, double extra) {
        double wind = w.strength() * extra;
        if (e instanceof LivingEntity living && !living.onGround()) wind *= profile.airFactor();
        double mass = mass(e);
        if (e.onGround()) wind -= profile.weight().threshold(mass);
        if (wind <= 0.0) return 0.0;
        double a = wind * multiplier(profile.multipliers(), e) * profile.weight().boost(mass);
        if (e instanceof LivingEntity living) {
            a *= 1.0 - Math.min(1.0, Math.max(0.0, living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)));
        }
        return Math.max(0.0, a);
    }

    /**
     * Push {@code e} by {@code w}; returns the acceleration applied (0 when capped or calm).
     *
     * <p>Projectiles get the two extra steps in {@link ProjectilePush} so their drift reads smoothly: a stuck one
     * is left alone, and a pushed one has its new velocity sent to clients this tick.</p>
     */
    public static double apply(Entity e, WindSample w, WindProfile profile, double extra) {
        if (ProjectilePush.stuck(e)) return 0.0;
        double a = accel(e, w, profile, extra) * taper(e.getDeltaMovement(), w);
        if (a <= 0.0) return 0.0;
        Vec3 v = e.getDeltaMovement();
        e.setDeltaMovement(v.x + w.dirX() * a, v.y, v.z + w.dirZ() * a);
        ProjectilePush.markPushed(e);
        return a;
    }

    /** 1 well under the downwind cap, easing to 0 across the last {@link #TAPER} of it. */
    static double taper(Vec3 v, WindSample w) {
        double downwind = v.x * w.dirX() + v.z * w.dirZ();
        return Math.max(0.0, Math.min(1.0, (MAX_DOWNWIND_SPEED - downwind) / TAPER));
    }
}
