package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Auto-spawns a 10-carriage train at the fixed world origin (0, {@link #TRAIN_Y}, 0)
 * on the first overworld login where no Dungeon Train ship exists yet, then
 * teleports every joining player to a random position alongside the current
 * train, facing it.
 *
 * Placement: ±{@link #X_OFFSET_MAX} blocks along X (train's travel axis),
 * {@link #PERP_MIN}..{@link #PERP_MAX} blocks on a random side (+Z or −Z),
 * stood on WORLD_SURFACE heightmap, yaw rotated to look toward the train.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerJoinEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int DEFAULT_CARRIAGE_COUNT = 10;
    private static final int TRAIN_Y = 150;
    private static final BlockPos TRAIN_ORIGIN = new BlockPos(0, TRAIN_Y, 0);
    private static final Vector3dc TRAIN_VELOCITY = new Vector3d(2.0, 0.0, 0.0);

    private static final double X_OFFSET_MAX = 200.0;
    private static final double PERP_MIN = 10.0;
    private static final double PERP_MAX = 40.0;

    private PlayerJoinEvents() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        LoadedServerShip trainShip = findTrain(level);
        if (trainShip == null) {
            LOGGER.info("[DungeonTrain] No train present — auto-spawning {} carriages at {}",
                DEFAULT_CARRIAGE_COUNT, TRAIN_ORIGIN);
            try {
                TrainAssembler.spawnTrain(level, TRAIN_ORIGIN, TRAIN_VELOCITY, DEFAULT_CARRIAGE_COUNT);
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] Starter train auto-spawn failed", t);
                return;
            }
            trainShip = findTrain(level);
            if (trainShip == null) {
                LOGGER.error("[DungeonTrain] Train assembly succeeded but ship not found in loaded set");
                return;
            }
        }

        placePlayerFacingTrain(level, player, trainShip);
    }

    private static LoadedServerShip findTrain(ServerLevel level) {
        for (LoadedServerShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (ship.getTransformProvider() instanceof TrainTransformProvider) {
                return ship;
            }
        }
        return null;
    }

    private static void placePlayerFacingTrain(ServerLevel level, ServerPlayer player, LoadedServerShip ship) {
        Vector3dc trainPos = ship.getTransform().getPosition();
        RandomSource rand = level.getRandom();

        double xOffset = (rand.nextDouble() * 2.0 - 1.0) * X_OFFSET_MAX;
        double sideSign = rand.nextBoolean() ? 1.0 : -1.0;
        double perpDist = PERP_MIN + rand.nextDouble() * (PERP_MAX - PERP_MIN);
        double zOffset = sideSign * perpDist;

        double px = trainPos.x() + xOffset;
        double pz = trainPos.z() + zOffset;
        int py = level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(px), Mth.floor(pz));

        player.teleportTo(level, px, py, pz, 0f, 0f);
        Vec3 lookTarget = new Vec3(trainPos.x(), trainPos.y() + 1.5, trainPos.z());
        player.lookAt(EntityAnchorArgument.Anchor.EYES, lookTarget);

        LOGGER.info("[DungeonTrain] Placed {} at ({},{},{}) looking at train centre ({},{},{})",
            player.getName().getString(),
            String.format("%.1f", px), py, String.format("%.1f", pz),
            String.format("%.1f", trainPos.x()), String.format("%.1f", trainPos.y()), String.format("%.1f", trainPos.z()));
    }
}
