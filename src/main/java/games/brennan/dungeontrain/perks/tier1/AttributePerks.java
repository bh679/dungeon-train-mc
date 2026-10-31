package games.brennan.dungeontrain.perks.tier1;

import games.brennan.dungeontrain.perks.PerkAttributeModifiers;
import games.brennan.dungeontrain.perks.PerkEffect;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The three flat attribute perks: Health (Tank, +half a heart per rank), Speed (Speedster, +1%
 * base movement speed per rank) and Damage (Knight, +0.1 attack damage per rank). Uncapped. Pure
 * amounts live in the static helpers so the numbers are unit-tested and easy to retune.
 */
public final class AttributePerks implements PerkEffect {

    /** Half a heart per rank. */
    public static final double HEALTH_PER_RANK = 1.0;
    /** 1% of base movement speed per rank. */
    public static final double SPEED_PER_RANK = 0.01;
    /** A tenth of a point of attack damage per rank. */
    public static final double DAMAGE_PER_RANK = 0.1;

    public static double healthBonus(int rank) {
        return Math.max(0, rank) * HEALTH_PER_RANK;
    }

    public static double speedMultiplierBonus(int rank) {
        return Math.max(0, rank) * SPEED_PER_RANK;
    }

    public static double damageBonus(int rank) {
        return Math.max(0, rank) * DAMAGE_PER_RANK;
    }

    @Override
    public void onRankChanged(ServerPlayer player, String perkId, int oldRank, int newRank) {
        switch (perkId) {
            case TierOnePerks.HEALTH -> applyHealth(player, newRank);
            case TierOnePerks.SPEED -> applySpeed(player, newRank);
            case TierOnePerks.DAMAGE -> applyDamage(player, newRank);
            default -> { }
        }
    }

    @Override
    public void onPlayerReady(ServerPlayer player) {
        applyHealth(player, Perks.rank(player, TierOnePerks.HEALTH));
        applySpeed(player, Perks.rank(player, TierOnePerks.SPEED));
        applyDamage(player, Perks.rank(player, TierOnePerks.DAMAGE));
    }

    private static void applyHealth(ServerPlayer player, int rank) {
        float before = player.getMaxHealth();
        PerkAttributeModifiers.apply(player, Attributes.MAX_HEALTH, TierOnePerks.HEALTH,
            healthBonus(rank), AttributeModifier.Operation.ADD_VALUE);
        float after = player.getMaxHealth();
        if (after > before) {
            // New hearts arrive full, the way a vanilla health boost does.
            player.setHealth(Math.min(after, player.getHealth() + (after - before)));
        } else if (player.getHealth() > after) {
            player.setHealth(after);
        }
    }

    private static void applySpeed(ServerPlayer player, int rank) {
        PerkAttributeModifiers.apply(player, Attributes.MOVEMENT_SPEED, TierOnePerks.SPEED,
            speedMultiplierBonus(rank), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    private static void applyDamage(ServerPlayer player, int rank) {
        PerkAttributeModifiers.apply(player, Attributes.ATTACK_DAMAGE, TierOnePerks.DAMAGE,
            damageBonus(rank), AttributeModifier.Operation.ADD_VALUE);
    }
}
