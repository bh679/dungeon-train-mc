package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.mixin.PlayerMobFeelingsAccessor;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Good Gifter's PlayerMob half: a gift the player hands a PlayerMob (its {@code creditGift}
 * seam, reached through {@code compat/PlayerMobSocialBridge}) raises the mob's feeling toward
 * the giver by {@link GoodGifterMath#playerMobFeelingBonus(int)} on top of the mob's own credit.
 * The ledger clamps feeling to its own 0 to 10 range.
 */
public final class GoodGifterPlayerMobs {

    private GoodGifterPlayerMobs() {}

    public static void onPlayerGift(ServerPlayer giver, PlayerMobEntity mob, ItemStack gift) {
        int rank = Perks.rank(giver, LoverPerkIds.GOOD_GIFTER);
        if (rank <= 0) return;
        float bonus = GoodGifterMath.playerMobFeelingBonus(rank);
        ((PlayerMobFeelingsAccessor) mob).dungeontrain$feelings().adjust(giver.getUUID(), bonus);
    }
}
