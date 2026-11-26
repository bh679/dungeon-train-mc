package games.brennan.dungeontrain.perks.effects.warlock;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

/**
 * Summon Lightning ({@link WarlockPerks#SUMMON_LIGHTNING}): look at a mob within
 * {@link WarlockPerks#LIGHTNING_RANGE} and press attack with an empty hand. The client spots the press
 * and the target ({@code client.warlock.SummonLightningInput}); the server checks it all again here.
 *
 * <p>The bolt is visual only, so it lights no blocks and converts nothing (no charged creeper, witch or
 * piglin). The perk deals {@link WarlockPerks#lightningDamage} itself, credited to the caster, and sets
 * the target alight. The caster pays {@link WarlockPerks#lightningCost} health, past ½♥ only by
 * chance ({@link WarlockPerks#lightningCostPercent}). A cast that would kill the caster is refused.
 * Creative casters pay nothing. A cast whose hit doesn't land (the target's hurt immunity, an
 * invulnerable target) costs nothing. Casts under {@link #MIN_INTERVAL_TICKS} apart count once.</p>
 */
public final class SummonLightning {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String KEY_NOT_ENOUGH = "gui.dungeontrain.warlock.lightning.not_enough_hearts";
    /** How long the target burns, in ticks. */
    static final int BURN_TICKS = 160;
    /** Casts closer together than this are dropped, so a double click or a held key is one cast. */
    static final int MIN_INTERVAL_TICKS = 10;
    static final String LAST_CAST_KEY = "dungeontrain:summon_lightning_last";
    /** Slack on the range check for the client's eye position and the target's size. */
    private static final double RANGE_SLACK = 3.0;

    private SummonLightning() {}

    static void cast(ServerPlayer player, int entityId) {
        int rank = Perks.rank(player, WarlockPerks.SUMMON_LIGHTNING);
        if (rank <= 0 || !player.isAlive() || player.isSpectator()) return;
        if (!player.getMainHandItem().isEmpty()) return;
        ServerLevel level = player.serverLevel();
        Entity entity = level.getEntity(entityId);
        if (!(entity instanceof LivingEntity target) || !target.isAlive() || target == player) return;
        double reach = WarlockPerks.LIGHTNING_RANGE + RANGE_SLACK;
        if (player.distanceToSqr(target) > reach * reach || !player.hasLineOfSight(target)) return;
        if (target instanceof Player other && !player.canHarmPlayer(other)) return;

        long now = player.server.overworld().getGameTime();
        long last = player.getPersistentData().getLong(LAST_CAST_KEY);
        if (now >= last && now - last < MIN_INTERVAL_TICKS) return;

        float cost = player.getAbilities().instabuild ? 0f : rollCost(player, rank);
        if (cost > 0f && player.getHealth() <= cost) {
            player.displayClientMessage(Component.translatable(KEY_NOT_ENOUGH), true);
            return;
        }
        DamageSource source = new DamageSource(
            level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.LIGHTNING_BOLT), player);
        // a hit that doesn't land (hurt immunity, an invulnerable target) costs nothing and shows nothing
        if (!target.hurt(source, WarlockPerks.lightningDamage(rank))) return;
        player.getPersistentData().putLong(LAST_CAST_KEY, now);
        if (cost > 0f) player.setHealth(player.getHealth() - cost);

        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(target.getX(), target.getY(), target.getZ());
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
        if (!target.fireImmune()) target.igniteForTicks(Math.max(BURN_TICKS, target.getRemainingFireTicks()));
        LOGGER.debug("[DT-Perks] summon-lightning rank {} by {} on {}: {} damage, cost {}", rank,
            player.getScoreboardName(), target.getName().getString(), WarlockPerks.lightningDamage(rank), cost);
    }

    /** The health this cast takes: the cost, or nothing when the chance past ½♥ misses. */
    private static float rollCost(ServerPlayer player, int rank) {
        double percent = WarlockPerks.lightningCostPercent(rank);
        if (percent < 100.0 && player.getRandom().nextDouble() * 100.0 >= percent) return 0f;
        return WarlockPerks.lightningCost(rank);
    }
}
