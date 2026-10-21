package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.difficulty.EffectSoftCaps;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Resistance V and above never makes an entity immune. Vanilla takes 20% off per level, so
 * level V blocks every hit; deep-train mobs roll far past that. From V up the Resistance
 * reduction is replaced with {@link EffectSoftCaps#resistanceDamageFraction}, which keeps
 * shrinking the damage without reaching zero.
 *
 * <p>The modifier runs inside vanilla's Resistance step, so damage that bypasses Resistance
 * (void, {@code /kill}) never reaches it. At that point {@code getNewDamage()} is still the
 * damage before Resistance: {@code DamageContainer#setReduction} subtracts only after its
 * modifiers return.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ResistanceSoftCapEvents {

    private ResistanceSoftCapEvents() {}

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity entity = event.getEntity();
        MobEffectInstance resistance = entity.getEffect(MobEffects.DAMAGE_RESISTANCE);
        if (resistance == null) return;
        int amplifier = resistance.getAmplifier();
        if (!EffectSoftCaps.isSoftCapped(amplifier)) return;

        double taken = EffectSoftCaps.resistanceDamageFraction(amplifier);
        event.addReductionModifier(DamageContainer.Reduction.MOB_EFFECTS,
                (container, vanillaReduction) -> (float) (container.getNewDamage() * (1.0 - taken)));
    }
}
