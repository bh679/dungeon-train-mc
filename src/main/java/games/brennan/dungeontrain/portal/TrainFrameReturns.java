package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.NetherPortalBandJump;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalLoadScreenPacket;
import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.PlotWorldPositions;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The way back to a portal frame built on a carriage. A trip out of such a frame
 * ({@code event/NetherPortalBandJump}) records here which frame it left; stepping into the exit portal it arrived
 * in then lands the player back in that frame, wherever the train has got to.
 *
 * <p>Kept in memory only, unlike {@link games.brennan.dungeontrain.world.NetherPortalLinks}: trains do not survive a
 * restart and neither would this. A carriage is named by its sub-level id and the frame by its offset from the
 * carriage's plot origin, never by a plot position, which could outlive the carriage and drop someone into the
 * void. A return only runs against a live carriage still holding the frame ({@link #liveFrame}); anything else is
 * forgotten and the trip goes wherever the exit portal would otherwise lead.</p>
 *
 * <p>A carriage the player has left far behind sits in Sable holding, so the return asks for it back and waits a
 * few seconds. The player is still standing in the exit portal meanwhile, on the ground, and walking out cancels.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrainFrameReturns {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long a return waits for a carriage to come back from holding. */
    private static final long WAKE_TIMEOUT_TICKS = 200L;

    /** How often a waiting return asks for another group back. */
    private static final long WAKE_RETRY_TICKS = 20L;

    /** A player this far (squared) from where they stepped in has walked out of the portal. */
    private static final double WALKED_OUT_SQR = 36.0;

    /** Where an exit portal leads back to. */
    record Origin(UUID trainId, int anchor, UUID subLevelId, BlockPos frameOffset, Block portal) {}

    /** A return waiting for its carriage. */
    private record Waiting(BlockPos exitFrame, BlockPos portalPos, Vec3 stoodAt, NetherPortalBandJump.Kind kind,
                           NetherPortalBandJump.ExitPortalFinder finder, long startTick, Set<UUID> tried) {}

    /** {@code handled == false} → this portal does not lead back to a train; otherwise {@code transition} may be null while a carriage wakes. */
    public record Trip(boolean handled, @Nullable DimensionTransition transition) {
        private static final Trip NONE = new Trip(false, null);
        private static final Trip WAIT = new Trip(true, null);
    }

    /** What a return does next. */
    enum Step { NONE, LAND, WAKE, FORGET }

    private static final Map<BlockPos, Origin> ORIGINS = new HashMap<>();
    private static final Map<UUID, Waiting> WAITING = new HashMap<>();

    private TrainFrameReturns() {}

    /**
     * The rule: no record → this is not a way back; the frame on a live carriage → land there; a live carriage that
     * lost the frame → forget it; a carriage that is not live but still registered → wake it; otherwise it is gone.
     */
    static Step decide(boolean recorded, boolean carriageLive, boolean frameStands, boolean carriageRegistered) {
        if (!recorded) return Step.NONE;
        if (carriageLive) return frameStands ? Step.LAND : Step.FORGET;
        return carriageRegistered ? Step.WAKE : Step.FORGET;
    }

    /** Record that the trip out of carriage frame {@code trainFrame} arrived at world frame {@code exitFrame}. */
    public static void remember(ServerLevel level, BlockPos exitFrame, BlockPos trainFrame) {
        ManagedShip ship = Shipyards.of(level).findAt(trainFrame);
        if (ship == null || !(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) return;
        ORIGINS.put(exitFrame.immutable(), new Origin(provider.getTrainId(), provider.getPIdx(), ship.subLevelId(),
            trainFrame.subtract(provider.getShipyardOrigin()), level.getBlockState(trainFrame).getBlock()));
    }

    /**
     * {@code player} stepped into the world frame {@code exitFrame}: the trip back to its train frame, a wait while
     * that carriage wakes, or {@link Trip#NONE} when this portal has no way back.
     */
    public static Trip begin(ServerLevel level, ServerPlayer player, BlockPos exitFrame, BlockPos portalPos,
                             NetherPortalBandJump.Kind kind, NetherPortalBandJump.ExitPortalFinder finder) {
        Origin origin = ORIGINS.get(exitFrame);
        Optional<ManagedShip> ship = origin == null ? Optional.empty() : liveCarriage(level, origin);
        Optional<BlockPos> frame = ship.isPresent() ? liveFrame(level, origin) : Optional.empty();
        Step step = decide(origin != null, ship.isPresent(), frame.isPresent(), origin != null && isRegistered(origin));
        return switch (step) {
            case NONE -> Trip.NONE;
            case LAND -> new Trip(true, landing(level, player, exitFrame, frame.get(), kind));
            case WAKE -> {
                Waiting waiting = new Waiting(exitFrame, portalPos, player.position(), kind, finder,
                    level.getGameTime(), new HashSet<>());
                WAITING.put(player.getUUID(), waiting);
                LOGGER.info("[DungeonTrain] portal at {}: waking the carriage it leads back to for {}", exitFrame,
                    player.getName().getString());
                wake(level, origin, waiting);
                yield Trip.WAIT;
            }
            case FORGET -> {
                forget(exitFrame, "its train frame is gone");
                yield Trip.NONE;
            }
        };
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (WAITING.isEmpty()) return;
        ServerLevel level = event.getServer().overworld();
        for (Map.Entry<UUID, Waiting> entry : new ArrayList<>(WAITING.entrySet())) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            Waiting waiting = entry.getValue();
            if (player == null || player.level() != level || player.isRemoved()
                    || player.position().distanceToSqr(waiting.stoodAt()) > WALKED_OUT_SQR) {
                WAITING.remove(entry.getKey());
                continue;
            }
            progress(level, player, waiting);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ORIGINS.clear();
        WAITING.clear();
    }

    /** One tick of a waiting return: land if the carriage is back, give up at the timeout, ask again now and then. */
    private static void progress(ServerLevel level, ServerPlayer player, Waiting waiting) {
        Origin origin = ORIGINS.get(waiting.exitFrame());
        Optional<BlockPos> frame = origin == null ? Optional.empty() : liveFrame(level, origin);
        long waited = level.getGameTime() - waiting.startTick();
        boolean carriageLive = origin != null && liveCarriage(level, origin).isPresent();
        if (frame.isPresent()) {
            WAITING.remove(player.getUUID());
            travel(player, landing(level, player, waiting.exitFrame(), frame.get(), waiting.kind()));
        } else if (carriageLive || waited >= WAKE_TIMEOUT_TICKS) {
            WAITING.remove(player.getUUID());
            forget(waiting.exitFrame(), carriageLive ? "its train frame is gone" : "its carriage did not come back in time");
            NetherPortalBandJump.Outcome outcome = NetherPortalBandJump.destination(level, player, waiting.portalPos(),
                waiting.kind(), waiting.finder());
            travel(player, outcome.transition());
        } else if (origin != null && waited % WAKE_RETRY_TICKS == 0L) {
            wake(level, origin, waiting);
        }
    }

    private static void travel(ServerPlayer player, @Nullable DimensionTransition transition) {
        if (transition != null) player.changeDimension(transition);
    }

    private static void wake(ServerLevel level, Origin origin, Waiting waiting) {
        TrainCarriageAppender.wakeHeldGroup(level, origin.trainId(), origin.anchor(), waiting.tried());
    }

    private static void forget(BlockPos exitFrame, String why) {
        ORIGINS.remove(exitFrame);
        LOGGER.info("[DungeonTrain] portal at {} no longer leads back to the train: {}", exitFrame, why);
    }

    /** Whether the train still has this exact carriage registered: an anchor can respawn as a new sub-level. */
    private static boolean isRegistered(Origin origin) {
        ManagedShip known = Trains.knownGroups(origin.trainId()).get(origin.anchor());
        return known != null && origin.subLevelId().equals(known.subLevelId());
    }

    /** The carriage, when it is loaded and driven by its train. */
    private static Optional<ManagedShip> liveCarriage(ServerLevel level, Origin origin) {
        return Trains.findById(level, origin.trainId()).stream()
            .map(Trains.Carriage::ship)
            .filter(ship -> origin.subLevelId().equals(ship.subLevelId()))
            .findFirst();
    }

    /** The frame's plot position, only when its carriage is live, stands at that position, and still holds the frame. */
    private static Optional<BlockPos> liveFrame(ServerLevel level, Origin origin) {
        Optional<ManagedShip> ship = liveCarriage(level, origin);
        if (ship.isEmpty() || !(ship.get().getKinematicDriver() instanceof TrainTransformProvider provider)) return Optional.empty();
        BlockPos frame = provider.getShipyardOrigin().offset(origin.frameOffset());
        ManagedShip at = Shipyards.of(level).findAt(frame);
        if (at == null || !origin.subLevelId().equals(at.subLevelId())) return Optional.empty();
        return level.getBlockState(frame).is(origin.portal()) ? Optional.of(frame) : Optional.empty();
    }

    /** Into the frame, as vanilla lands a player in a portal: its cooldown stops the frame sending them straight back. */
    private static DimensionTransition landing(ServerLevel level, ServerPlayer player, BlockPos exitFrame, BlockPos frame,
                                               NetherPortalBandJump.Kind kind) {
        BlockState state = level.getBlockState(frame);
        boolean alongX = state.getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS).orElse(Direction.Axis.X) == Direction.Axis.X;
        Vec3 arrive = PlotWorldPositions.worldPoint(level, frame, alongX ? 1.0 : 0.5, 0.0, alongX ? 0.5 : 1.0);
        int fromX = player.getBlockX();
        LOGGER.info("[DungeonTrain] nether portal: {} x={} back to the train frame at ({}, {}, {}), from exit {}",
            player.getName().getString(), fromX, (int) arrive.x, (int) arrive.y, (int) arrive.z, exitFrame);
        NetherPortalBandJump.awardCrossing(level, player, kind, fromX, (int) arrive.x);
        DungeonTrainNet.sendTo(player, new PortalLoadScreenPacket());
        return new DimensionTransition(level, arrive, Vec3.ZERO, player.getYRot(), player.getXRot(),
            DimensionTransition.PLAY_PORTAL_SOUND.then(entity -> {
                // The carriage is a moving sub-level this client does not have yet: hold them at the frame's floor.
                DungeonTrainNet.sendTo(player, new SpawnDeckHoldPacket(arrive.y, SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS));
                PortalRoomRescue.holdHarmless(player, level.getGameTime());
            }));
    }
}
