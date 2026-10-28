package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
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
 * The creatures that matter to each player's death scene: anything they fought (either way) and
 * anyone they exchanged gifts with (a friend). The death scene asks for the nearest one still alive
 * and nearby (other than the killer), or failing that a nearby pet of theirs, to show "the other
 * mob". Per player, newest first, at most {@link #MAX}; cleared on respawn and logout. Server only.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RecentMobContacts {

    static final int MAX = 8;
    /** Only a creature this close to the body is worth a shot. */
    private static final double MAX_DISTANCE = 32.0;

    private static final Map<UUID, Deque<Integer>> CONTACTS = new HashMap<>();

    private RecentMobContacts() {}

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) remember(player, event.getTarget());
    }

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) remember(player, event.getSource().getEntity());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        CONTACTS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CONTACTS.remove(event.getEntity().getUUID());
    }

    /** Remember a creature the player had to do with; players and non-living things are ignored. */
    public static void remember(ServerPlayer player, Entity entity) {
        if (entity instanceof LivingEntity && !(entity instanceof Player)) {
            CONTACTS.put(player.getUUID(), remember(CONTACTS.get(player.getUUID()), entity.getId(), MAX));
        }
    }

    /** By UUID (PlayerMob gift seams hand over ids, not entities). */
    public static void remember(ServerPlayer player, UUID entityId) {
        ServerLevel level = player.serverLevel();
        remember(player, level.getEntity(entityId));
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

    /**
     * The nearest remembered creature (fought, or a friend) still alive and near the player, else
     * the nearest pet of theirs nearby; {@code excludeId} (the killer) never; -1 if none.
     */
    public static int nearestAlive(ServerPlayer player, int excludeId) {
        int best = -1;
        double bestDist = MAX_DISTANCE * MAX_DISTANCE;
        Deque<Integer> ids = CONTACTS.get(player.getUUID());
        if (ids != null) {
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
        }
        if (best >= 0) return best;
        AABB near = player.getBoundingBox().inflate(MAX_DISTANCE);
        for (TamableAnimal pet : player.serverLevel().getEntitiesOfClass(TamableAnimal.class, near,
                t -> t.isAlive() && t.isTame() && t.isOwnedBy(player) && t.getId() != excludeId)) {
            double d = pet.distanceToSqr(player);
            if (d < bestDist) {
                bestDist = d;
                best = pet.getId();
            }
        }
        return best;
    }
}
