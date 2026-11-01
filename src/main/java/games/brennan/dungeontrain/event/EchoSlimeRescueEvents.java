package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.compat.EchoIdentity;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.primitives.AABBdc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Bounce Back</b> ({@code secrete_menu/knockback_slime_echo_rescue}): knock your own echo back onto
 * the train with a Knockback Slime while it is out over the void.
 *
 * <p>{@link #recordSlimeHit} runs from {@code KnockbackSlimeItem#hurtEnemy}. A hit counts as a rescue
 * attempt only when the target is the striker's own echo, it is off the train (beside every carriage's
 * bounding box, so a hop on the deck can't count) and nothing in the world column under it stops a fall
 * before the bottom of the world. Carriage blocks live in Sable plot space, so they never read as ground
 * there. There is no time limit, so a hit that sends the echo up into the sky still counts: the
 * {@link #onLevelTick} scan waits for the next time the echo stands on solid ground, and credits the
 * striker if that ground is a carriage and the echo is still alive. Landing anywhere else, dying
 * (fall damage, the void) or unloading ends the attempt.</p>
 *
 * <p>Twin space never counts, for the same reason it doesn't for Reboarder: a portal room is off the
 * deck and over nothing without anyone being shoved off a moving train.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EchoSlimeRescueEvents {

    /** {@code gameplay_action} for {@code secrete_menu/knockback_slime_echo_rescue}. */
    public static final String RESCUE_ADVANCEMENT_ACTION = "knockback_slime_echo_rescue";

    /** echo UUID → the slime hit that may yet land it back on the train. */
    private static final Map<UUID, RescueHit> PENDING = new HashMap<>();

    private EchoSlimeRescueEvents() {}

    /** A qualifying slime hit: who struck, and in which level the echo was. */
    record RescueHit(UUID playerUuid, ResourceKey<Level> dimension) {}

    /** What one scan concludes about a pending rescue. */
    enum RescueDecision { CREDIT, KEEP, DROP }

    /**
     * Remember a Knockback Slime hit on {@code target} if it is {@code player}'s own echo, off the train,
     * over the void. Call after the knockback has been applied; the position read is the hit position.
     */
    public static void recordSlimeHit(ServerPlayer player, LivingEntity target) {
        if (!(target.level() instanceof ServerLevel level)) return;
        if (!EchoIdentity.isOwnEcho(target, player.getUUID())) return;
        if (PortalTwinSpace.isInside(level, Mth.floor(target.getX()), target.getY())) return;
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        if (carriages.isEmpty()) return;
        if (CarriageDeck.isOnCarriageDeck(carriages, target)) return;
        if (!isBesideTrain(carriages, target)) return;
        if (!isOverVoid(level, target)) return;
        PENDING.put(target.getUUID(), new RescueHit(player.getUUID(), level.dimension()));
    }

    /**
     * Every tick while a rescue is pending (it is a rare, short-lived state), so the landing is judged
     * on the tick it happens, before the echo can walk off the deck. Fall damage is applied during the
     * landing move, so an echo the fall killed already reads as dead here.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        for (Map.Entry<UUID, RescueHit> entry : new ArrayList<>(PENDING.entrySet())) {
            if (entry.getValue().dimension() != level.dimension()) continue;
            Entity echo = level.getEntity(entry.getKey());
            boolean alive = echo != null && echo.isAlive();
            Boolean onDeck = alive ? CarriageDeck.isOnCarriageDeck(carriages, echo) : null;
            boolean inTwinSpace = alive && PortalTwinSpace.isInside(level, Mth.floor(echo.getX()), echo.getY());
            boolean onGround = alive && echo.onGround();
            switch (step(onDeck, onGround, inTwinSpace)) {
                case CREDIT -> {
                    credit(level, entry.getValue().playerUuid());
                    PENDING.remove(entry.getKey());
                }
                case DROP -> PENDING.remove(entry.getKey());
                case KEEP -> { }
            }
        }
    }

    /**
     * Pure decision for one scan, table-tested by {@code EchoSlimeRescueStepTest}.
     *
     * @param onDeck      the echo is over a carriage deck, or {@code null} when it is dead or unloaded
     * @param onGround    the echo is standing on solid ground (vanilla {@code onGround}, which holds on carriages)
     * @param inTwinSpace the echo is inside portal twin space
     */
    static RescueDecision step(Boolean onDeck, boolean onGround, boolean inTwinSpace) {
        if (onDeck == null || inTwinSpace) return RescueDecision.DROP;
        if (!onGround) return RescueDecision.KEEP;
        return onDeck ? RescueDecision.CREDIT : RescueDecision.DROP;
    }

    /** A stale hit must not survive into the next world loaded in this JVM (singleplayer). */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    private static void credit(ServerLevel level, UUID playerUuid) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerUuid);
        if (player == null) return;
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, RESCUE_ADVANCEMENT_ACTION);
    }

    /** Horizontally outside every carriage's unpadded bounding box: off the side or ends of the train. */
    private static boolean isBesideTrain(List<Trains.Carriage> carriages, Entity e) {
        double x = e.getX();
        double z = e.getZ();
        for (Trains.Carriage c : carriages) {
            AABBdc bb = c.ship().worldAABB();
            if (x >= bb.minX() && x <= bb.maxX() && z >= bb.minZ() && z <= bb.maxZ()) return false;
        }
        return true;
    }

    /** Nothing in the world column from the entity's feet down to the bottom of the world. */
    private static boolean isOverVoid(ServerLevel level, Entity e) {
        int x = Mth.floor(e.getX());
        int z = Mth.floor(e.getZ());
        int min = level.getMinBuildHeight();
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int from = Math.min(Mth.floor(e.getY()), top);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = from; y >= min; y--) {
            if (!level.getBlockState(pos.set(x, y, z)).isAir()) return false;
        }
        return true;
    }
}
