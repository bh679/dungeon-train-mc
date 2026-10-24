package games.brennan.dungeontrain.perks.effects.knight;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.KillCredit;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;

/**
 * Applies the Knight's combat perks ({@link KnightPerks}).
 *
 * <p>Crits ride NeoForge's {@link CriticalHitEvent}, fired from {@code Player.attack} on both sides.
 * Only the server rolls: a random roll cannot be matched across sides, and the server's verdict is
 * what vanilla broadcasts as crit particles (attacker included), so the client's own event is left
 * alone. A vanilla jump crit is never downgraded.
 *
 * <p>Kill Buff and Vampirism credit kills exactly as {@code RunStatsEvents} does: through
 * {@link KillCredit#resolveKiller}, so a knock into the void counts and PlayerMobs count, and never
 * for killing yourself.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class KnightCombatEvents {

    private KnightCombatEvents() {}

    @SubscribeEvent
    public static void onCriticalHit(CriticalHitEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || event.isCriticalHit()) return;
        int rank = Perks.rank(player, KnightPerks.CRITS);
        if (!KnightPerks.rollCrit(rank, player.getRandom())) return;
        event.setCriticalHit(true);
        event.setDamageMultiplier(KnightPerks.CRIT_DAMAGE_MULTIPLIER);
    }

    @SubscribeEvent
    public static void onKill(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        if (!(KillCredit.resolveKiller(victim, event.getSource()).orElse(null)
                instanceof ServerPlayer killer)) return;
        if (victim == killer || !killer.isAlive()) return;

        float heal = 0f;
        int killBuff = Perks.rank(killer, KnightPerks.KILL_BUFF);
        if (killBuff > 0) {
            grantStrength(killer, KnightPerks.strengthDurationTicks(killBuff));
            heal += KnightPerks.killBuffHeal(killBuff);
        }
        heal += KnightPerks.vampirismHeal(Perks.rank(killer, KnightPerks.VAMPIRISM));
        if (heal > 0f) killer.heal(heal);
    }

    private static void grantStrength(ServerPlayer player, int ticks) {
        MobEffectInstance current = player.getEffect(MobEffects.DAMAGE_BOOST);
        int remaining = current == null ? 0 : current.getDuration();
        // A stronger Strength from a potion stands; only Strength I (or nothing) is refreshed.
        if (current != null && current.getAmplifier() > KnightPerks.STRENGTH_AMPLIFIER) return;
        if (!KnightPerks.shouldRefreshStrength(remaining, ticks)) return;
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, ticks,
            KnightPerks.STRENGTH_AMPLIFIER, false, true, true));
    }
}
