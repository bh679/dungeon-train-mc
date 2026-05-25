package games.brennan.dungeontrain.registry.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * "Warmth Of The Fire" — applied while standing in proximity of a lit soul
 * campfire (see {@link games.brennan.dungeontrain.event.SoulCampfireHealEvents}).
 *
 * <p>Heals {@code 1.0 HP} every {@link #HEAL_PERIOD_TICKS} game ticks while
 * the effect is active. The effect is re-applied each scan tick by the
 * proximity scanner with a duration just long enough to bridge to the next
 * scan, so leaving the campfire's radius lets the effect expire naturally
 * within a couple of seconds.</p>
 */
public final class WarmthOfTheFireEffect extends MobEffect {

    /** 20 ticks/sec × 4 s. Net rate ≈ 1 HP / 4 s — intentionally very slow ("slow healing"). */
    public static final int HEAL_PERIOD_TICKS = 80;

    /** Soul-fire blue particle/HUD tint. */
    private static final int PARTICLE_COLOUR = 0x4DC4FF;

    public WarmthOfTheFireEffect() {
        super(MobEffectCategory.BENEFICIAL, PARTICLE_COLOUR);
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.getHealth() < entity.getMaxHealth()) {
            entity.heal(1.0F);
        }
        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return duration % HEAL_PERIOD_TICKS == 0;
    }
}
