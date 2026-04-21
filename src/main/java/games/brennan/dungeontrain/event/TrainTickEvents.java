package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.TrainWindowManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-tick server logic that lets Dungeon Trains carve straight through the
 * world: every tick we kill non-player entities inside each train's world AABB,
 * and every {@link #BLOCK_CLEAR_PERIOD_TICKS} ticks we destroy non-ship blocks
 * in a forward look-ahead slab. Clearing blocks ahead keeps the VS collider
 * moving over empty space — it achieves "train cannot be stopped" without
 * touching VS collision internals (no public API for that in 2.4.x).
 *
 * Blocks and entities are removed WITHOUT drops or XP ({@code destroyBlock(pos,
 * false)} and {@code entity.discard()}).
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrainTickEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    // See {@link games.brennan.dungeontrain.train.TrainTransformProvider}'s JITTER_LOGGER.
    private static final Logger JITTER_LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    // Train moves 2 m/s ≈ 0.1 block/tick (MVP velocity in TrainCommand). A 10-tick
    // period clears blocks twice per second; an 8-block look-ahead leaves 7+ blocks
    // of runway between clears. If velocity is raised later, scale LOOKAHEAD_BLOCKS
    // to at least velocity * period / 20 * safety_margin.
    private static final int BLOCK_CLEAR_PERIOD_TICKS = 10;
    private static final int LOOKAHEAD_BLOCKS = 8;

    private static int tickCounter = 0;

    private TrainTickEvents() {}

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        // Rolling-window manager runs every tick regardless of whether we're also
        // carving terrain — it only adds/removes carriages when a player crosses
        // a carriage boundary, so the cost is negligible on idle ticks.
        TrainWindowManager.onLevelTick(level);

        List<LoadedServerShip> trains = findTrains(level);
        if (trains.isEmpty()) {
            tickCounter++;
            return;
        }

        for (LoadedServerShip ship : trains) {
            killEntitiesIn(level, ship);
            logCarryProbeIfMutated(level, ship);
        }

        if (tickCounter % BLOCK_CLEAR_PERIOD_TICKS == 0) {
            for (LoadedServerShip ship : trains) {
                clearBlocksAhead(level, ship);
            }
        }
        tickCounter++;
    }

    /**
     * Stage 1 probe for hypothesis H4 — on ticks where the window manager
     * just swapped blocks under a player, log the player's deltaMovement,
     * vehicle, and the block they're standing on. Gated on {@code
     * provider.getLastMutationTick() == currentTick} so the probe is silent
     * on ticks without a voxel mutation.
     */
    private static void logCarryProbeIfMutated(ServerLevel level, LoadedServerShip ship) {
        if (!JITTER_LOGGER.isDebugEnabled()) return;
        if (!(ship.getTransformProvider() instanceof TrainTransformProvider provider)) return;
        long currentTick = level.getGameTime();
        if (provider.getLastMutationTick() != currentTick) return;

        AABBdc aabb = ship.getWorldAABB();
        AABB mcAabb = new AABB(
            aabb.minX(), aabb.minY(), aabb.minZ(),
            aabb.maxX(), aabb.maxY(), aabb.maxZ()
        );
        List<Player> carried = level.getEntitiesOfClass(Player.class, mcAabb, Entity::isAlive);
        for (Player p : carried) {
            BlockPos below = p.blockPosition().below();
            BlockState stateBelow = level.getBlockState(below);
            JITTER_LOGGER.debug(
                "[carry] tick={} player={} delta={} vehicle={} blockBelow={}@{} onGround={}",
                currentTick, p.getName().getString(),
                p.getDeltaMovement(),
                p.getVehicle() == null ? "null" : p.getVehicle().getType().toString(),
                stateBelow.getBlock(), below, p.onGround());
        }
    }

    private static List<LoadedServerShip> findTrains(ServerLevel level) {
        List<LoadedServerShip> trains = new ArrayList<>();
        for (LoadedServerShip loaded : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (loaded.getTransformProvider() instanceof TrainTransformProvider) {
                trains.add(loaded);
            }
        }
        return trains;
    }

    private static void killEntitiesIn(ServerLevel level, LoadedServerShip ship) {
        AABBdc aabb = ship.getWorldAABB();
        AABB mcAabb = new AABB(
            aabb.minX(), aabb.minY(), aabb.minZ(),
            aabb.maxX(), aabb.maxY(), aabb.maxZ()
        );
        List<Entity> victims = level.getEntitiesOfClass(
            Entity.class, mcAabb,
            e -> !(e instanceof Player) && e.isAlive()
        );
        for (Entity e : victims) {
            e.discard();
        }
    }

    private static void clearBlocksAhead(ServerLevel level, LoadedServerShip ship) {
        if (!(ship.getTransformProvider() instanceof TrainTransformProvider provider)) return;
        Vector3dc velocity = provider.getTargetVelocity();

        AABBdc aabb = ship.getWorldAABB();
        double expX = Math.signum(velocity.x()) * LOOKAHEAD_BLOCKS;
        double expY = Math.signum(velocity.y()) * LOOKAHEAD_BLOCKS;
        double expZ = Math.signum(velocity.z()) * LOOKAHEAD_BLOCKS;

        int minX = (int) Math.floor(Math.min(aabb.minX(), aabb.minX() + expX));
        int minY = (int) Math.floor(Math.min(aabb.minY(), aabb.minY() + expY));
        int minZ = (int) Math.floor(Math.min(aabb.minZ(), aabb.minZ() + expZ));
        int maxX = (int) Math.floor(Math.max(aabb.maxX(), aabb.maxX() + expX));
        int maxY = (int) Math.floor(Math.max(aabb.maxY(), aabb.maxY() + expY));
        int maxZ = (int) Math.floor(Math.max(aabb.maxZ(), aabb.maxZ() + expZ));

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int destroyed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    // Never destroy ship-owned blocks — our own carriages or any other VS ship.
                    if (VSGameUtilsKt.getShipObjectManagingPos(level, cursor) != null) continue;
                    level.destroyBlock(cursor.immutable(), false);
                    destroyed++;
                }
            }
        }
        if (destroyed > 0) {
            LOGGER.debug("[DungeonTrain] Train id={} cleared {} blocks ahead", ship.getId(), destroyed);
        }
    }
}
