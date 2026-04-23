package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.TrainWindowManager;
import games.brennan.dungeontrain.tunnel.TunnelGenerator;
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

    // Train moves 2 m/s ≈ 0.1 block/tick (MVP velocity in TrainCommand). A 10-tick
    // period clears blocks twice per second; an 8-block look-ahead leaves 7+ blocks
    // of runway between clears. If velocity is raised later, scale LOOKAHEAD_BLOCKS
    // to at least velocity * period / 20 * safety_margin.
    private static final int BLOCK_CLEAR_PERIOD_TICKS = 10;
    private static final int LOOKAHEAD_BLOCKS = 8;
    // ~2 Hz at 20 TPS — picks up chunks that were loaded at spawn time (and so
    // never re-fire ChunkEvent.Load) plus any that moved into range since the
    // last scan. Idempotent in TrackGenerator, so re-scans are cheap (set-hit
    // per chunk). The +5 offset keeps the fill off the same tick as
    // clearBlocksAhead — otherwise both heavy operations collide every 20
    // ticks and spike the server tick past its 50 ms budget.
    private static final int TRACK_FILL_PERIOD_TICKS = 10;
    private static final int TRACK_FILL_PHASE_OFFSET = 5;
    // Tunnel fill drains at the same period as track fill but lands on a
    // separate tick (offset 2) so block-clear (offset 0), track-fill (5), and
    // tunnel-fill (2) never collide on a 10-tick cycle. 1 chunk per call keeps
    // per-tick cost flat — ~13×9×16 block writes per chunk worst case.
    private static final int TUNNEL_FILL_PERIOD_TICKS = 10;
    private static final int TUNNEL_FILL_PHASE_OFFSET = 2;

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
        }

        if (tickCounter % BLOCK_CLEAR_PERIOD_TICKS == 0) {
            for (LoadedServerShip ship : trains) {
                clearBlocksAhead(level, ship);
            }
        }

        if (DungeonTrainConfig.getGenerateTracks()
            && Math.floorMod(tickCounter, TRACK_FILL_PERIOD_TICKS) == TRACK_FILL_PHASE_OFFSET) {
            for (LoadedServerShip ship : trains) {
                if (ship.getTransformProvider() instanceof TrainTransformProvider provider) {
                    TrackGenerator.fillRenderDistance(level, ship, provider);
                }
            }
        }

        if (DungeonTrainConfig.getGenerateTunnels()
            && Math.floorMod(tickCounter, TUNNEL_FILL_PERIOD_TICKS) == TUNNEL_FILL_PHASE_OFFSET) {
            for (LoadedServerShip ship : trains) {
                if (ship.getTransformProvider() instanceof TrainTransformProvider provider) {
                    TunnelGenerator.fillRenderDistance(level, ship, provider);
                }
            }
        }
        tickCounter++;
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
