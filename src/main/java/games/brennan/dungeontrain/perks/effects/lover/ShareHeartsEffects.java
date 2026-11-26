package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Share Hearts (Lover, tier 2): shift + right-click a friend ({@link LoverFriends}) with anything
 * but food in the main hand to move some of your health to them, by {@link ShareHeartsMath}. Food
 * keeps its normal gift (Good Gifter, vanilla feeding). A target at full health, or a giver who
 * would drop under one heart, spends nothing.
 *
 * <p>The client cancels the same click so its own item use doesn't fire; the server decides
 * everything else. The interact packet is sent before the client's event, so cancelling there
 * never stops the server hearing the click.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ShareHeartsEffects {

    /** giver → (target → game time the giver may give that target again). */
    private static final Map<UUID, Map<UUID, Long>> READY_AT = new HashMap<>();

    private ShareHeartsEffects() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (event.getHand() != InteractionHand.MAIN_HAND || !player.isSecondaryUseActive()) return;
        if (!(event.getTarget() instanceof LivingEntity target)) return;
        ItemStack stack = event.getItemStack();
        if (stack.getFoodProperties(player) != null) return; // food keeps its gift
        int rank = Perks.rank(player, LoverPerkIds.SHARE_HEARTS);
        if (rank <= 0 || target.getHealth() >= target.getMaxHealth()) return;

        if (player instanceof ServerPlayer giver) {
            if (!LoverFriends.isFriend(giver, target)) return;
            give(giver, target, rank);
        } else if (!looksLikeFriend(target)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        READY_AT.clear();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        READY_AT.remove(event.getEntity().getUUID());
    }

    /** The client's guess at the friend test; the server's {@link LoverFriends} is the real one. */
    private static boolean looksLikeFriend(Entity target) {
        return target instanceof Player
            || (target instanceof OwnableEntity pet && pet.getOwnerUUID() != null)
            || target instanceof PlayerMobEntity;
    }

    private static void give(ServerPlayer giver, LivingEntity target, int rank) {
        long now = giver.level().getGameTime();
        Map<UUID, Long> mine = READY_AT.computeIfAbsent(giver.getUUID(), k -> new HashMap<>());
        Long ready = mine.get(target.getUUID());
        if (ready != null && now < ready) return;

        int cost = ShareHeartsMath.cost(rank);
        int gift = ShareHeartsMath.rollGift(rank, giver.getRandom().nextDouble());
        float moved = ShareHeartsMath.given(gift, target.getMaxHealth() - target.getHealth(), giver.getHealth(), cost);
        if (moved <= 0) return;

        mine.put(target.getUUID(), now + ShareHeartsMath.COOLDOWN_TICKS);
        giver.setHealth(giver.getHealth() - cost);
        target.heal(moved);
        if (giver.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HEART, target.getX(), target.getY() + target.getBbHeight() * 0.8, target.getZ(),
                Math.max(1, Math.round(moved)), 0.3, 0.2, 0.3, 0.0);
            level.playSound(null, target.blockPosition(), SoundEvents.ALLAY_ITEM_GIVEN, SoundSource.PLAYERS, 0.7F, 1.2F);
        }
    }
}
