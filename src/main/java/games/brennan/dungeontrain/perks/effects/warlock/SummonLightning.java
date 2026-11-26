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
 * Creative casters pay nothing.</p>
 */
public final class SummonLightning {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String KEY_NOT_ENOUGH = "gui.dungeontrain.warlock.lightning.not_enough_hearts";
    /** How long the target burns, in ticks. */
    static final int BURN_TICKS = 160;
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

        float cost = player.getAbilities().instabuild ? 0f : rollCost(player, rank);
        if (cost > 0f && player.getHealth() <= cost) {
            player.displayClientMessage(Component.translatable(KEY_NOT_ENOUGH), true);
            return;
        }
        if (cost > 0f) player.setHealth(player.getHealth() - cost);

        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(target.getX(), target.getY(), target.getZ());
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
        DamageSource source = new DamageSource(
            level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.LIGHTNING_BOLT), player);
        target.hurt(source, WarlockPerks.lightningDamage(rank));
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
