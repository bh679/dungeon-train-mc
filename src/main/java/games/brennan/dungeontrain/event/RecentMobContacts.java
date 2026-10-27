package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The creatures each player has lately had anything to do with: fought (either way), right-clicked
 * (feeding, leashing, a PlayerMob, opening a trade), traded with, photographed, exchanged gifts
 * with, or simply looked at (a look ray every {@link #LOOK_EVERY_TICKS} ticks). The death scene asks
 * for the nearest one still alive (other than the killer) to show "the other mob". Per player,
 * newest first, at most {@link #MAX}; cleared on respawn and logout. Server only.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RecentMobContacts {

    static final int MAX = 8;
    /** Only a creature this close to the body is worth a shot. */
    private static final double MAX_DISTANCE = 32.0;
    /** How often the look ray runs, and how far it reaches. */
    private static final int LOOK_EVERY_TICKS = 10;
    private static final double LOOK_REACH = 24.0;

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
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player) remember(player, event.getTarget());
    }

    @SubscribeEvent
    public static void onTrade(TradeWithVillagerEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) remember(player, event.getAbstractVillager());
    }

    /** Eye contact: whatever creature the player is looking at. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % LOOK_EVERY_TICKS != 0) return;
        if (!player.isAlive()) return;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(LOOK_REACH));
        AABB box = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player.level(), player, eye, end, box,
            e -> e instanceof LivingEntity && !(e instanceof Player) && e.isAlive());
        if (hit != null) remember(player, hit.getEntity());
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

    public static void rememberAll(ServerPlayer player, Collection<? extends Entity> entities) {
        for (Entity e : entities) remember(player, e);
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

    /** The nearest remembered creature that is still alive and near the player, excluding {@code excludeId}; -1 if none. */
    public static int nearestAlive(ServerPlayer player, int excludeId) {
        Deque<Integer> ids = CONTACTS.get(player.getUUID());
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
