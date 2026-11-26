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

    /** A living friend, for gifts and the aura. */
    public static boolean isFriend(Player lover, Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive() && isFriendKind(lover, entity);
    }

    /**
     * The kind test alone, without the liveness check: Save Ally asks it from {@code LivingDeathEvent},
     * where the victim's health is already 0. A pet counts when its owner is a player (or not loaded);
     * a pet a PlayerMob or Echo owns does not.
     */
    public static boolean isFriendKind(Player lover, Entity entity) {
        if (lover == null || !(entity instanceof LivingEntity) || entity == lover || entity.isSpectator()) return false;
        if (entity instanceof Player) return true;
        if (entity instanceof OwnableEntity pet) {
            if (pet.getOwnerUUID() == null) return false;
            LivingEntity owner = pet.getOwner();
            return owner == null || owner instanceof Player;
        }
        if (entity instanceof PlayerMobEntity mob) return likes(mob.feelingToward(lover));
        return false;
    }

    /** True when a PlayerMob's feeling toward the Lover counts as friendly. */
    static boolean likes(float feeling) {
        return feeling > RunStatsEvents.FRIEND_FEELING_MIN;
    }
}
