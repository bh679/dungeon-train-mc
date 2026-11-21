package games.brennan.dungeontrain.perks.effects.knight;

import com.mojang.logging.LogUtils;
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
import org.slf4j.Logger;

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

    private static final Logger LOGGER = LogUtils.getLogger();

    private KnightCombatEvents() {}

    @SubscribeEvent
    public static void onCriticalHit(CriticalHitEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        int rank = Perks.rank(player, KnightPerks.CRITS);
        float multiplier = KnightPerks.rollCritMultiplier(rank, player.getRandom());
        // A jump crit keeps its 1.5x; the perk only ever raises the multiplier.
        if (multiplier <= 1f) return;
        if (event.isCriticalHit() && multiplier <= event.getDamageMultiplier()) return;
        event.setCriticalHit(true);
        event.setDamageMultiplier(multiplier);
        LOGGER.debug("[DT-Perks] crits rank {}: perk crit x{} by {} on {}", rank, multiplier,
            player.getScoreboardName(), event.getTarget().getName().getString());
    }

    @SubscribeEvent
    public static void onKill(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        if (!(KillCredit.resolveKiller(victim, event.getSource()).orElse(null)
                instanceof ServerPlayer killer)) return;
        if (victim == killer || !killer.isAlive()) return;

        int killBuff = Perks.rank(killer, KnightPerks.KILL_BUFF);
        if (killBuff > 0) grantStrength(killer, killBuff);
        int vampirism = Perks.rank(killer, KnightPerks.VAMPIRISM);
        float heal = KnightPerks.rollVampirismHeal(vampirism, killer.getRandom());
        if (heal > 0f) killer.heal(heal);
        if (killBuff > 0 || vampirism > 0) {
            LOGGER.debug("[DT-Perks] kill by {}: kill-buff rank {} (strength {} for {} t), vampirism rank {} heal {}",
                killer.getScoreboardName(), killBuff, KnightPerks.strengthAmplifier(killBuff) + 1,
                KnightPerks.strengthDurationTicks(killBuff), vampirism, heal);
        }
    }

    private static void grantStrength(ServerPlayer player, int rank) {
        int amplifier = KnightPerks.strengthAmplifier(rank);
        int ticks = KnightPerks.strengthDurationTicks(rank);
        MobEffectInstance current = player.getEffect(MobEffects.DAMAGE_BOOST);
        int currentAmplifier = current == null ? -1 : current.getAmplifier();
        int remaining = current == null ? 0 : current.getDuration();
        if (!KnightPerks.shouldRefreshStrength(currentAmplifier, remaining, amplifier, ticks)) return;
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, ticks, amplifier, false, true, true));
    }
}
