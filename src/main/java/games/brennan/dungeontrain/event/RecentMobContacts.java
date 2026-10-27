package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The last few mobs each player fought: hit by the player, or hurt the player. The death scene
 * asks for the nearest one still alive (other than the killer) to show "the other mob".
 * Per player, newest first, at most {@link #MAX} ids; cleared on respawn and logout. Server only.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RecentMobContacts {

    static final int MAX = 8;
    /** Only a mob this close to the body is worth a shot. */
    private static final double MAX_DISTANCE = 32.0;

    private static final Map<UUID, Deque<Integer>> CONTACTS = new HashMap<>();

    private RecentMobContacts() {}

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getTarget() instanceof Mob mob) {
            remember(player.getUUID(), mob.getId());
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getSource().getEntity() instanceof Mob mob) {
            remember(player.getUUID(), mob.getId());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        CONTACTS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CONTACTS.remove(event.getEntity().getUUID());
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static void remember(UUID player, int id) {
        LOGGER.info("[DungeonTrain] Mob contact remembered: player={} mob={}", player, id);
        CONTACTS.put(player, remember(CONTACTS.get(player), id, MAX));
    }

    /** Pure: a new deque with {@code id} newest, no duplicates, at most {@code max} entries. */
    static Deque<Integer> remember(Deque<Integer> previous, int id, int max) {
        Deque<Integer> next = new ArrayDeque<>();
        next.addFirst(id);
        if (previous != null) {
            for (int old : previous) {
                if (old != id && next.size() < max) next.addLast(old);
            }
        }
        return next;
    }

    /** The nearest remembered mob that is still alive and near the player, excluding {@code excludeId}; -1 if none. */
    public static int nearestAlive(ServerPlayer player, int excludeId) {
        Deque<Integer> ids = CONTACTS.get(player.getUUID());
        LOGGER.info("[DungeonTrain] Mob contacts for {}: {} (excluding {})", player.getGameProfile().getName(), ids, excludeId);
        if (ids == null) return -1;
        int best = -1;
        double bestDist = MAX_DISTANCE * MAX_DISTANCE;
        for (int id : ids) {
            if (id == excludeId) continue;
            Entity entity = player.serverLevel().getEntity(id);
            if (!(entity instanceof LivingEntity living) || living instanceof Player || !living.isAlive()) continue;
            double d = living.distanceToSqr(player);
            if (d < bestDist) {
                bestDist = d;
                best = id;
            }
        }
        return best;
    }
}
