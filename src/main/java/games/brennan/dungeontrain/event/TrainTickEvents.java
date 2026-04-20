package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.ActiveTrains;
import games.brennan.dungeontrain.train.CarriageTemplate;
import games.brennan.dungeontrain.train.ContentsPopulator;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-tick server logic that lets Dungeon Trains carve straight through the
 * world (kill entities inside the AABB, clear blocks ahead) AND lazily
 * populates carriage contents as players approach.
 *
 * <p>Entities tagged with {@link ContentsPopulator#TAG_CONTENT} (mobs spawned
 * as carriage contents) are exempt from the kill sweep.
 *
 * <p>Blocks and entities are removed WITHOUT drops or XP ({@code destroyBlock(pos,
 * false)} and {@code entity.discard()}).
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrainTickEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int BLOCK_CLEAR_PERIOD_TICKS = 10;
    private static final int PROXIMITY_CHECK_PERIOD_TICKS = 10;
    private static final int LOOKAHEAD_BLOCKS = 8;

    private static int tickCounter = 0;

    private TrainTickEvents() {}

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

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

        if (tickCounter % PROXIMITY_CHECK_PERIOD_TICKS == 0) {
            checkProximityAndPopulate(level);
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
            e -> !(e instanceof Player)
                && e.isAlive()
                && !e.getTags().contains(ContentsPopulator.TAG_CONTENT)
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

    /**
     * For each registered train, check every un-populated carriage against
     * the nearest player. If within {@link ContentsPopulator#PROXIMITY_BLOCKS},
     * populate that carriage and flip the flag. Drops registry entries for
     * ships that are no longer loaded.
     */
    private static void checkProximityAndPopulate(ServerLevel level) {
        Map<Long, ActiveTrains.TrainData> snapshot = ActiveTrains.snapshot();
        if (snapshot.isEmpty()) return;

        for (Map.Entry<Long, ActiveTrains.TrainData> entry : snapshot.entrySet()) {
            long shipId = entry.getKey();
            ActiveTrains.TrainData data = entry.getValue();
            LoadedServerShip ship = VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips().getById(shipId);
            if (ship == null) {
                ActiveTrains.remove(shipId);
                continue;
            }

            Matrix4dc shipToWorld = ship.getShipToWorld();
            BlockPos originShipLocal = data.originShipLocal();

            for (int i = 0; i < data.carriageCount(); i++) {
                if (data.populated()[i]) continue;
                Vector3d shipLocalCentre = new Vector3d(
                    originShipLocal.getX() + i * CarriageTemplate.LENGTH + CarriageTemplate.LENGTH / 2.0,
                    originShipLocal.getY() + CarriageTemplate.HEIGHT / 2.0,
                    originShipLocal.getZ() + CarriageTemplate.WIDTH / 2.0
                );
                Vector3d worldCentre = new Vector3d();
                shipToWorld.transformPosition(shipLocalCentre, worldCentre);

                Player nearest = level.getNearestPlayer(
                    worldCentre.x, worldCentre.y, worldCentre.z,
                    ContentsPopulator.PROXIMITY_BLOCKS,
                    false
                );
                if (nearest == null) continue;

                ContentsPopulator.populate(level, ship, i, originShipLocal, data.specs().get(i));
                data.populated()[i] = true;
                LOGGER.debug(
                    "[DungeonTrain] Populated carriage {} of ship {} ({})",
                    i, shipId, data.specs().get(i).contents()
                );
            }
        }
    }
}
