package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkEffect;
import games.brennan.dungeontrain.perks.PerkEffects;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;

/** Perk ids of the Lover class, kebab-case from the perk names in the tree. */
public final class LoverPerkIds {

    public static final String PET_HEALTH = "pet-health";
    public static final String GOOD_GIFTER = "good-gifter";

    private LoverPerkIds() {}

    /** Re-applies Pet Health to the owner's loaded pets whenever its rank changes (menu, debug, death reset). */
    private static final PerkEffect PET_HEALTH_REFRESH = new PerkEffect() {
        @Override
        public void onRankChanged(net.minecraft.server.level.ServerPlayer player, String perkId, int oldRank, int newRank) {
            if (PET_HEALTH.equals(perkId)) PetHealthEffects.onRankChanged(player);
        }
    };

    /** Marks the built Lover perks, registers their menu stats and Pet Health's rank hook. */
    public static void declare() {
        BuiltPerks.markBuilt(PET_HEALTH, GOOD_GIFTER);
        PerkEffects.register(PET_HEALTH_REFRESH);
        PerkStats.register(PET_HEALTH, "pet_health", r -> PerkStats.plus(PetHealthMath.bonusHealth(r), Unit.HEARTS));
        PerkStats.register(GOOD_GIFTER, "gift_strength", r -> PerkStats.num(
            r <= 0 ? 1.0 : GoodGifterMath.strength(GoodGifterMath.Offering.RIGHT_FOOD, r), Unit.MULTIPLIER));
        PerkStats.register(GOOD_GIFTER, "gift_warmth", r -> PerkStats.plus(GoodGifterMath.playerMobFeelingBonus(r), Unit.PLAIN));
    }
}
