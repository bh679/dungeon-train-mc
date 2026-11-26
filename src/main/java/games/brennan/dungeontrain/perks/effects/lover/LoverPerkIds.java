package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkEffect;
import games.brennan.dungeontrain.perks.PerkEffects;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
import games.brennan.dungeontrain.perks.aura.AuraTicker;
import games.brennan.dungeontrain.perks.aura.Auras;

/** Perk ids of the Lover class, kebab-case from the perk names in the tree. */
public final class LoverPerkIds {

    public static final String PET_HEALTH = "pet-health";
    public static final String GOOD_GIFTER = "good-gifter";
    public static final String SHARE_HEARTS = "share-hearts";
    public static final String AURA = "aura";
    public static final String SPARE_ENEMIES = "spare-enemies";
    public static final String SAVE_ALLY = "save-ally";

    private LoverPerkIds() {}

    /** Re-applies Pet Health to the owner's loaded pets whenever its rank changes (menu, debug, death reset). */
    private static final PerkEffect PET_HEALTH_REFRESH = new PerkEffect() {
        @Override
        public void onRankChanged(net.minecraft.server.level.ServerPlayer player, String perkId, int oldRank, int newRank) {
            if (PET_HEALTH.equals(perkId)) PetHealthEffects.onRankChanged(player);
        }
    };

    /** Marks the built Lover perks, registers their menu stats, Pet Health's rank hook and the Aura. */
    public static void declare() {
        BuiltPerks.markBuilt(PET_HEALTH, GOOD_GIFTER, SHARE_HEARTS, AURA, SPARE_ENEMIES, SAVE_ALLY);
        PerkEffects.register(PET_HEALTH_REFRESH);
        PerkEffects.register(AuraTicker.READY);
        Auras.register(LoverAura.def());
        PerkStats.register(PET_HEALTH, "pet_health", r -> PerkStats.plus(PetHealthMath.bonusHealth(r), Unit.HEARTS));
        PerkStats.register(GOOD_GIFTER, "gift_strength", r -> PerkStats.num(
            r <= 0 ? 1.0 : GoodGifterMath.strength(GoodGifterMath.Offering.RIGHT_FOOD, r), Unit.MULTIPLIER));
        PerkStats.register(GOOD_GIFTER, "gift_warmth", r -> PerkStats.plus(GoodGifterMath.playerMobFeelingBonus(r), Unit.PLAIN));
        PerkStats.register(SHARE_HEARTS, "cost", r -> PerkStats.num(ShareHeartsMath.cost(r), Unit.HEARTS));
        PerkStats.register(SHARE_HEARTS, "gift", LoverPerkIds::giftValue);
        PerkStats.register(SPARE_ENEMIES, "flees_below", r -> PerkStats.num(SpareEnemiesMath.fleePercent(r), Unit.PERCENT));
        PerkStats.register(SAVE_ALLY, "range", r -> PerkStats.num(SaveAllyMath.range(r), Unit.BLOCKS));
        PerkStats.register(SAVE_ALLY, "every", r -> PerkStats.num(SaveAllyMath.everyMinutes(r), Unit.MINUTES));
    }

    /** Share Hearts' gift: certain, or "base or base + half a heart" on a point that rolls for more. */
    static PerkStats.Value giftValue(int rank) {
        PerkStats.Value base = PerkStats.num(ShareHeartsMath.baseGift(rank), Unit.HEARTS);
        if (!ShareHeartsMath.hasExtraChance(rank)) return base;
        return new PerkStats.Or(base, PerkStats.num(ShareHeartsMath.baseGift(rank) + 1, Unit.HEARTS));
    }
}
