package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.advancement.PlayerMobSocialTracker;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.primitives.AABBdc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wires PlayerMob-interaction signals to the social/aggression advancement
 * triggers in {@link ModAdvancementTriggers}.
 *
 * <ul>
 *   <li><b>The Denamed</b> — {@link PlayerInteractEvent.EntityInteract}: using
 *       a custom-named name tag on a PlayerMob fires {@code named_playermob}.</li>
 *   <li><b>Reboarder</b> — {@link AttackEntityEvent} stamps the most recent
 *       player-strike on each PlayerMob (with whether it was on the train at
 *       the time); the {@link LevelTickEvent.Post} scan fires
 *       {@code pushed_playermob_off_train} when a recently-struck mob that was
 *       on the train has since left the train footprint. The recent-hit gate
 *       distinguishes a deliberate shove from the PlayerMob's own by-design
 *       falls (it has recovery AI that climbs back on).</li>
 *   <li>Logout — clears the player's transient {@link PlayerMobSocialTracker}
 *       state.</li>
 * </ul>
 *
 * <p>Item give/receive (A Silent Friend / Friends) is captured separately by
 * {@code mixin.PlayerMobGiveItemMixin} / {@code mixin.PlayerMobPickupMixin}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerMobAdvancementEvents {

    /** Entity-id namespace of the bundled playermob mod (mirrors {@code TrainTickEvents}). */
    private static final String PLAYERMOB_NAMESPACE = "playermob";

    /** Off-train scan cadence — matches {@link BoardingProgressEvents}. */
    private static final int SCAN_PERIOD_TICKS = 10;

    /** How long after a player's strike a mob leaving the train still counts as "pushed off". */
    private static final long PUSH_WINDOW_TICKS = 100L;

    /** Horizontal pad on each carriage AABB — matches {@link BoardingProgressEvents}. */
    private static final double HORIZONTAL_PADDING = 1.0;

    /** mob UUID → most recent player strike. */
    private static final Map<UUID, ReboarderHit> RECENT_HITS = new HashMap<>();

    private PlayerMobAdvancementEvents() {}

    /** A player strike on a PlayerMob: who, when, and whether the mob was on the train then. */
    private record ReboarderHit(UUID playerUuid, long tick, boolean wasOnTrain) {}

    // ---------------- The Denamed ----------------

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!isPlayerMob(event.getTarget())) return;
        var stack = event.getItemStack();
        if (!stack.is(Items.NAME_TAG)) return;
        // Vanilla only renames when the name tag carries a custom name (set on
        // an anvil); match that so a blank name tag doesn't grant the award.
        if (!stack.has(DataComponents.CUSTOM_NAME)) return;
        ModAdvancementTriggers.NAMED_PLAYERMOB.get().trigger(player);
    }

    // ---------------- Reboarder ----------------

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!isPlayerMob(target)) return;
        if (!(target.level() instanceof ServerLevel level)) return;
        boolean onTrain = isOnTrainFootprint(Trains.allCarriages(level), target);
        RECENT_HITS.put(target.getUUID(),
            new ReboarderHit(player.getUUID(), level.getGameTime(), onTrain));
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        if (RECENT_HITS.isEmpty()) return;

        long now = level.getGameTime();
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        // Snapshot so we can remove entries while iterating.
        for (Map.Entry<UUID, ReboarderHit> entry : new ArrayList<>(RECENT_HITS.entrySet())) {
            UUID mobUuid = entry.getKey();
            ReboarderHit hit = entry.getValue();
            Entity mob = level.getEntity(mobUuid);

            // Gone, dead, or stale → stop tracking.
            if (mob == null || !mob.isAlive() || now - hit.tick() > PUSH_WINDOW_TICKS) {
                RECENT_HITS.remove(mobUuid);
                continue;
            }
            // Was on the train when struck and has since left the footprint → pushed off.
            if (hit.wasOnTrain() && !carriages.isEmpty() && !isOnTrainFootprint(carriages, mob)) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(hit.playerUuid());
                if (player != null) {
                    ModAdvancementTriggers.PUSHED_PLAYERMOB_OFF_TRAIN.get().trigger(player);
                }
                RECENT_HITS.remove(mobUuid);
            }
        }
    }

    // ---------------- That's What Friends Are For ----------------

    /**
     * Fires when the player kills a hostile mob that is currently targeting a
     * PlayerMob — coming to a passenger's defence. A mob keeps its target
     * through death, so checking {@code getTarget()} in the death event
     * reliably catches the "kill the thing attacking a passenger" case.
     * Mirrors the kill-credit shape of {@code RunStatsEvents.onMobKilled}.
     */
    @SubscribeEvent
    public static void onMobKilled(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (!(victim instanceof Mob mob)) return;
        if (isPlayerMob(mob.getTarget())) {
            ModAdvancementTriggers.DEFENDED_PLAYERMOB.get().trigger(player);
        }
    }

    // ---------------- Logout cleanup ----------------

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerMobSocialTracker.forget(player.getUUID());
    }

    // ---------------- Helpers ----------------

    private static boolean isPlayerMob(Entity entity) {
        return entity != null
            && PLAYERMOB_NAMESPACE.equals(EntityType.getKey(entity.getType()).getNamespace());
    }

    /**
     * True when {@code e} is within any carriage's padded world AABB — the
     * same geometry {@link BoardingProgressEvents} uses to decide a player is
     * "on the train" (horizontal pad to bridge group joints; +1 above to
     * count standing on a roof).
     */
    private static boolean isOnTrainFootprint(List<Trains.Carriage> carriages, Entity e) {
        double x = e.getX();
        double y = e.getY();
        double z = e.getZ();
        for (Trains.Carriage c : carriages) {
            AABBdc bb = c.ship().worldAABB();
            if (x < bb.minX() - HORIZONTAL_PADDING || x > bb.maxX() + HORIZONTAL_PADDING) continue;
            if (y < bb.minY() || y > bb.maxY() + 1.0) continue;
            if (z < bb.minZ() - HORIZONTAL_PADDING || z > bb.maxZ() + HORIZONTAL_PADDING) continue;
            return true;
        }
        return false;
    }
}
