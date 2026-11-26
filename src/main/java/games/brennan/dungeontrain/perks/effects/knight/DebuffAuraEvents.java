package games.brennan.dungeontrain.perks.effects.knight;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * Debuff Aura's half-heart floor, so its kills stay the player's. Vanilla Poison already stops at half a
 * heart; Wither does not, so wither damage from an aura copy (aura copies are ambient, a wither
 * skeleton's hit is not) is cut to leave the mob at half a heart. The aura itself runs in
 * {@link games.brennan.dungeontrain.perks.aura.AuraTicker}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DebuffAuraEvents {

    /** Health the aura's Wither never takes a mob below: half a heart. */
    static final float FLOOR_HEALTH = 1.0F;

    private DebuffAuraEvents() {}

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        if (!event.getSource().is(DamageTypes.WITHER) || event.getSource().getEntity() != null) return;
        LivingEntity victim = event.getEntity();
        MobEffectInstance wither = victim.getEffect(MobEffects.WITHER);
        if (wither == null || !wither.isAmbient()) return;
        event.setNewDamage(flooredDamage(victim.getHealth(), event.getNewDamage()));
    }

    /** The damage that leaves {@code health} at no less than {@link #FLOOR_HEALTH}; never negative. */
    static float flooredDamage(float health, float damage) {
        return Math.max(0F, Math.min(damage, health - FLOOR_HEALTH));
    }
}
