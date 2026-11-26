package games.brennan.dungeontrain.perks.effects.tank;

import games.brennan.dungeontrain.perks.PerkAttributeModifiers;
import games.brennan.dungeontrain.perks.PerkEffect;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Tank Armour: a permanent {@code ARMOR} modifier of {@link TankPerks#armourBonus}, set when the rank
 * changes and again when the player entity is fresh (login, respawn, dimension change).
 */
final class TankArmourPerk implements PerkEffect {

    @Override
    public void onRankChanged(ServerPlayer player, String perkId, int oldRank, int newRank) {
        if (TankPerks.ARMOUR.equals(perkId)) apply(player, newRank);
    }

    @Override
    public void onPlayerReady(ServerPlayer player) {
        apply(player, Perks.rank(player, TankPerks.ARMOUR));
    }

    private static void apply(ServerPlayer player, int rank) {
        PerkAttributeModifiers.apply(player, Attributes.ARMOR, TankPerks.ARMOUR,
            TankPerks.armourBonus(rank), AttributeModifier.Operation.ADD_VALUE);
    }
}
