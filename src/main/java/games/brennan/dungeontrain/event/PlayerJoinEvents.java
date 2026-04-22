package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriageTemplate;
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
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Auto-spawns a 10-carriage train at the fixed world origin (0, trainY, 0)
 * — where {@code trainY} comes from {@link DungeonTrainConfig#getTrainY()} —
 * on the first overworld login where no Dungeon Train ship exists yet, then
 * teleports every joining player to a random position alongside the current
 * train, facing it.
 *
 * Placement: ±{@link #X_OFFSET_MAX} blocks along X (train's travel axis),
 * {@link #PERP_MIN}..{@link #PERP_MAX} blocks on a random side (+Z or −Z),
 * stood on WORLD_SURFACE heightmap, camera aimed at the train via
 * {@link ServerPlayer#lookAt}.
 *
 * When spawning a new train, the player's target position is computed first
 * and passed as the {@code spawnerWorldPos} to {@link TrainAssembler#spawnTrain},
 * so the train's initial carriage window is already centered where the player
 * will stand — zero churn on the rolling-window manager's first tick.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerJoinEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int DEFAULT_CARRIAGE_COUNT = 10;
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
            int trainY = DungeonTrainConfig.getTrainY();
            BlockPos trainOrigin = new BlockPos(0, trainY, 0);

            // Compute the intended player target up-front so the train's initial
            // carriage window lines up with where the player will stand.
            Vector3d approxTrainCenter = new Vector3d(
                trainOrigin.getX() + (CarriageTemplate.LENGTH * DEFAULT_CARRIAGE_COUNT) / 2.0,
                trainOrigin.getY() + CarriageTemplate.HEIGHT / 2.0,
                trainOrigin.getZ() + CarriageTemplate.WIDTH / 2.0
            );
            PlayerTarget target = pickPlayerTarget(level, approxTrainCenter);

            // Teleport the player to their final position BEFORE spawning the
            // train. If we teleport after assembly, VS's packet listener sees
            // the movement while the ship's chunks are mid-registration and
            // logs "moved while colliding with unloaded ships" — the client
            // gets snapped back to the default world spawn and every
            // subsequent move is rejected for the same reason, leaving the
            // player permanently stuck. With no ships in the level yet, the
            // teleport is unambiguous.
            player.teleportTo(level, target.px, target.py, target.pz, 0f, 0f);

            LOGGER.info("[DungeonTrain] No train present — auto-spawning {} carriages at {}",
                DEFAULT_CARRIAGE_COUNT, trainOrigin);
            ServerShip newShip;
            try {
                Vector3d spawnerPos = new Vector3d(target.px, target.py, target.pz);
                newShip = TrainAssembler.spawnTrain(level, trainOrigin, TRAIN_VELOCITY, DEFAULT_CARRIAGE_COUNT, spawnerPos);
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] Starter train auto-spawn failed", t);
                return;
            }
            if (newShip == null) {
                LOGGER.error("[DungeonTrain] Train assembly returned null ship");
                return;
            }
            lookAtShip(player, newShip);
            LOGGER.info("[DungeonTrain] Placed {} at ({},{},{}) near new train id={}",
                player.getName().getString(),
                String.format("%.1f", target.px), target.py, String.format("%.1f", target.pz),
                newShip.getId());
            return;
        }

        // Existing train — ship is fully registered in VS, teleport is safe.
        Vector3dc trainPos = trainShip.getTransform().getPosition();
        Vector3d trainCenter = new Vector3d(trainPos.x(), trainPos.y(), trainPos.z());
        PlayerTarget target = pickPlayerTarget(level, trainCenter);
        teleportAndLookAt(level, player, trainShip, target);
    }

    private static LoadedServerShip findTrain(ServerLevel level) {
        for (LoadedServerShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (ship.getTransformProvider() instanceof TrainTransformProvider) {
                return ship;
            }
        }
        return null;
    }

    private static PlayerTarget pickPlayerTarget(ServerLevel level, Vector3dc trainCenter) {
        RandomSource rand = level.getRandom();
        double xOffset = (rand.nextDouble() * 2.0 - 1.0) * X_OFFSET_MAX;
        double sideSign = rand.nextBoolean() ? 1.0 : -1.0;
        double perpDist = PERP_MIN + rand.nextDouble() * (PERP_MAX - PERP_MIN);
        double zOffset = sideSign * perpDist;

        double px = trainCenter.x() + xOffset;
        double pz = trainCenter.z() + zOffset;
        int bx = Mth.floor(px);
        int bz = Mth.floor(pz);
        int py = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);

        // Guard against heightmap returning minBuildHeight for columns with no
        // terrain (observed on the raised-floor Dungeon Train preset), which
        // spawns the player inside the Y=32 floor layer. Walk up from the
        // proposed Y until we find two blocks of air for the player's hitbox.
        int maxY = level.getMaxBuildHeight() - 2;
        while (py < maxY && !canStandAt(level, bx, py, bz)) {
            py++;
        }
        return new PlayerTarget(px, py, pz);
    }

    private static boolean canStandAt(ServerLevel level, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.above();
        return level.getBlockState(feet).isAir() && level.getBlockState(head).isAir();
    }

    private static void teleportAndLookAt(
        ServerLevel level,
        ServerPlayer player,
        ServerShip ship,
        PlayerTarget target
    ) {
        Vector3dc trainPos = ship.getTransform().getPosition();

        player.teleportTo(level, target.px, target.py, target.pz, 0f, 0f);
        lookAtShip(player, ship);

        LOGGER.info("[DungeonTrain] Placed {} at ({},{},{}) looking at train centre ({},{},{})",
            player.getName().getString(),
            String.format("%.1f", target.px), target.py, String.format("%.1f", target.pz),
            String.format("%.1f", trainPos.x()), String.format("%.1f", trainPos.y()), String.format("%.1f", trainPos.z()));
    }

    private static void lookAtShip(ServerPlayer player, ServerShip ship) {
        Vector3dc trainPos = ship.getTransform().getPosition();
        Vec3 lookTarget = new Vec3(trainPos.x(), trainPos.y() + 1.5, trainPos.z());
        player.lookAt(EntityAnchorArgument.Anchor.EYES, lookTarget);
    }

    private record PlayerTarget(double px, int py, double pz) {}
}
