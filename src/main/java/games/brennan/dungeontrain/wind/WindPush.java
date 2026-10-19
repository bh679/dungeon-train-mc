package games.brennan.dungeontrain.wind;

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

    /** Push {@code e} by {@code w}; {@code extra} multiplies on top (e.g. the sneak factor). */
    public static void apply(Entity e, WindSample w, WindProfile.Multipliers m, double extra) {
        double a = w.strength() * multiplier(m, e) * extra;
        if (e instanceof LivingEntity living) {
            a *= 1.0 - Math.min(1.0, Math.max(0.0, living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)));
        }
        if (a <= 0.0) return;
        Vec3 v = e.getDeltaMovement();
        if (v.x * w.dirX() + v.z * w.dirZ() >= MAX_DOWNWIND_SPEED) return;
        e.setDeltaMovement(v.x + w.dirX() * a, v.y, v.z + w.dirZ() * a);
    }
}
