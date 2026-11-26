package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.event.RunStatsEvents;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Who a Lover's perks (Share Hearts, Aura, Save Ally) count as a friend: any other player, any
 * tamed pet (an owned entity), and a PlayerMob that likes the Lover (its feeling toward them is
 * above {@link RunStatsEvents#FRIEND_FEELING_MIN}, the same line run stats use). Never the Lover.
 */
public final class LoverFriends {

    private LoverFriends() {}

    public static boolean isFriend(Player lover, Entity entity) {
        if (lover == null || !(entity instanceof LivingEntity living) || entity == lover) return false;
        if (!living.isAlive() || living.isSpectator()) return false;
        if (entity instanceof Player other) return !other.isSpectator();
        if (entity instanceof OwnableEntity pet) return pet.getOwnerUUID() != null;
        if (entity instanceof PlayerMobEntity mob) return likes(mob.feelingToward(lover));
        return false;
    }

    /** True when a PlayerMob's feeling toward the Lover counts as friendly. */
    static boolean likes(float feeling) {
        return feeling > RunStatsEvents.FRIEND_FEELING_MIN;
    }
}
