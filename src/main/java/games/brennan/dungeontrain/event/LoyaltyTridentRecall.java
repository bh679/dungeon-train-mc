package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.ProjectileOwnerAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A player's Loyalty trident always comes back.
 *
 * <p>Vanilla only flies a Loyalty trident home while it ticks and its owner is in the same dimension.
 * On a moving train that fails all the time: the train carries the thrower away, the trident's chunk
 * stops ticking and unloads, and it sits frozen there for good. It is also lost when the thrower goes
 * through a portal, and when it falls into the void (vanilla discards it 64 blocks below the world).</p>
 *
 * <p>So every Loyalty trident a player throws is tracked, and at the end of each level tick one is
 * handed straight back to its owner (inventory, or at their feet if it's full) when its chunk is no
 * longer entity-ticking, the owner is in another dimension, or it has dropped below the world. Chunks
 * stop ticking before they unload, so this runs before the trident is saved: no copy comes back when
 * the chunk reloads. While it still ticks near its owner, vanilla's own return flight is left alone.
 * An offline, dead or spectating owner gets nothing; vanilla decides, as before.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LoyaltyTridentRecall {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<ThrownTrident> TRACKED = Collections.newSetFromMap(new IdentityHashMap<>());

    private LoyaltyTridentRecall() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof ThrownTrident trident)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (trident.pickup == AbstractArrow.Pickup.DISALLOWED) return;
        if (ownerUuid(trident) == null) return;
        ItemStack stack = trident.getPickupItemStackOrigin();
        if (EnchantmentHelper.getTridentReturnToOwnerAcceleration(level, stack, trident) <= 0) return;
        TRACKED.add(trident);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof ThrownTrident trident) TRACKED.remove(trident);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TRACKED.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (TRACKED.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        List<ThrownTrident> due = new ArrayList<>();
        for (ThrownTrident trident : TRACKED) {
            if (trident.level() != level || trident.isRemoved()) continue;
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUuid(trident));
            if (owner == null) continue;
            boolean recall = shouldRecall(owner.isAlive(), owner.isSpectator(),
                    owner.level() == level,
                    level.isPositionEntityTicking(trident.blockPosition()),
                    trident.getY() < level.getMinBuildHeight());
            if (recall) due.add(trident);
        }
        // Discarding fires EntityLeaveLevelEvent, which edits TRACKED, so recall outside the loop.
        for (ThrownTrident trident : due) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUuid(trident));
            if (owner != null) recall(trident, owner);
        }
    }

    /**
     * Whether a tracked trident with an online owner goes straight back: never to a dead or spectating
     * owner, and otherwise only once vanilla can no longer bring it home itself.
     */
    static boolean shouldRecall(boolean ownerAlive, boolean ownerSpectator, boolean sameDimension,
                                boolean entityTicking, boolean belowWorld) {
        if (!ownerAlive || ownerSpectator) return false;
        return !sameDimension || !entityTicking || belowWorld;
    }

    private static void recall(ThrownTrident trident, ServerPlayer owner) {
        if (trident.pickup == AbstractArrow.Pickup.ALLOWED) {
            ItemStack stack = trident.getPickupItemStackOrigin().copy();
            if (!owner.getInventory().add(stack)) owner.drop(stack, false);
        }
        trident.discard();
        owner.level().playSound(null, owner.getX(), owner.getY(), owner.getZ(),
                SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.0F, 1.0F);
        LOGGER.debug("[LoyaltyTridentRecall] returned trident to {}", owner.getGameProfile().getName());
    }

    private static UUID ownerUuid(ThrownTrident trident) {
        return ((ProjectileOwnerAccessor) trident).dungeontrain$getOwnerUUID();
    }
}
