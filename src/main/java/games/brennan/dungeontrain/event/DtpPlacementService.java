package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deferred second half of {@code /dtp <x>}. {@code DtpCommand} spawns a fresh
 * train seeded at the requested world-X and parks the player in a safe
 * holding spot above the assembly footprint, then queues them here. Each
 * request runs two phases:
 *
 * <ol>
 *   <li><b>WAITING</b>: a freshly-assembled Sable group's {@code canonicalPos} is null
 *       until its first physics tick (the same race {@link PlayerJoinEvents} handles on
 *       login), so this retries once per overworld tick until
 *       {@link PlayerJoinEvents#findNearestFlatbedTarget} resolves a settled deck. The
 *       player is pinned at the holding spot meanwhile. Under load (a cold region
 *       generating, a heavy client) that first tick can take many seconds, so the wait
 *       is long while the new train exists and short only when no train turned up at all.</li>
 *   <li><b>LANDING</b>: the player is dropped on the deck, and then watched. A teleport
 *       onto a moving ship is not proof the player is on it: if the client has not yet
 *       received the ship's collision, the deck hold keeps them at deck height while the
 *       train can slide out from under them, and before the playable start there is no
 *       ground under the track. A player seen falling below the deck is put back on the
 *       current nearest pad, up to {@link #MAX_RELANDS} times (issue #2133).</li>
 * </ol>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DtpPlacementService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Wait for the new train's first physics tick while its groups exist. 20 TPS, so 600 ≈ 30s. */
    static final int SPAWN_CAPTURE_TICKS = 600;

    /** Give up much sooner when no train group exists at all: nothing is coming. */
    static final int NO_TRAIN_TICKS = 100;

    /** How long a landing is watched. Outlasts the client deck hold plus its post-check. */
    static final int LANDING_WATCH_TICKS = SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS + 80;

    /** A player this far below the deck top has fallen off or through the train. */
    static final double FALL_MARGIN_BLOCKS = 3.0;

    /** Re-landings allowed per /dtp before giving up. */
    static final int MAX_RELANDS = 3;

    /** The holding spot may sag this far before the player is put back on it. */
    private static final double HOLD_SAG_BLOCKS = 1.0;

    /** Hold coordinate meaning "no holding spot": the player is not pinned while waiting. */
    private static final double NO_HOLD = Double.NaN;

    /** Most carriages listed in a timeout diagnostic. */
    private static final int MAX_DIAGNOSTIC_CARRIAGES = 8;

    enum Phase { WAITING, LANDING }

    /** What a LANDING tick decides. */
    enum LandingStep { KEEP_WATCHING, LANDED, RELAND, GIVE_UP }

    private record PendingDtp(ServerLevel trainLevel, double targetX, double holdY, double holdZ,
                              Phase phase, int ticks, int relands) {
        PendingDtp withTicks(int newTicks) {
            return new PendingDtp(trainLevel, targetX, holdY, holdZ, phase, newTicks, relands);
        }

        PendingDtp landing(int newRelands) {
            return new PendingDtp(trainLevel, targetX, holdY, holdZ, Phase.LANDING, 0, newRelands);
        }

        PendingDtp waiting() {
            return new PendingDtp(trainLevel, targetX, holdY, holdZ, Phase.WAITING, 0, relands);
        }
    }

    /** Player UUID → in-flight /dtp request. Cleared on landing/timeout/logout. */
    private static final Map<UUID, PendingDtp> PENDING = new ConcurrentHashMap<>();

    private DtpPlacementService() {}

    /**
     * Queue {@code player} for deferred flatbed placement once the train seeded at {@code targetX}
     * settles. {@code holdY}/{@code holdZ} is the holding spot {@code DtpCommand} parked them at.
     */
    public static void enqueue(ServerPlayer player, ServerLevel trainLevel, double targetX, double holdY, double holdZ) {
        PENDING.put(player.getUUID(), new PendingDtp(trainLevel, targetX, holdY, holdZ, Phase.WAITING, 0, 0));
    }

    /**
     * Queue {@code player} with no holding spot to pin them at, for callers that park them
     * somewhere walkable (the respawn wake path's ground pose).
     */
    public static void enqueue(ServerPlayer player, ServerLevel trainLevel, double targetX) {
        enqueue(player, trainLevel, targetX, NO_HOLD, NO_HOLD);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (PENDING.isEmpty()) return;

        MinecraftServer server = level.getServer();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        Iterator<Map.Entry<UUID, PendingDtp>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingDtp> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            PendingDtp pending = entry.getValue();
            PendingDtp next = pending.phase() == Phase.WAITING
                ? tickWaiting(player, pending, data)
                : tickLanding(player, pending, data);
            if (next == null) {
                it.remove();
            } else {
                entry.setValue(next);
            }
        }
    }

    // ---- WAITING ----------------------------------------------------------------------------

    /** @return the next state, or {@code null} once the request is finished. */
    private static PendingDtp tickWaiting(ServerPlayer player, PendingDtp pending, DungeonTrainWorldData data) {
        if (placeOnDeck(player, pending, data)) {
            return pending.landing(pending.relands());
        }

        pinAtHoldingSpot(player, pending);

        int ticks = pending.ticks() + 1;
        boolean anyGroup = !Trains.allCarriages(pending.trainLevel()).isEmpty();
        if (!waitTimedOut(ticks, anyGroup)) {
            return pending.withTicks(ticks);
        }

        LOGGER.warn("[DungeonTrain] /dtp placement timed out for {} after {} ticks — releasing at holding spot",
            player.getName().getString(), ticks);
        logWaitDiagnostics(pending, data);
        player.setInvulnerable(false);
        player.sendSystemMessage(Component.literal(
            "The train didn't settle in time — you've been left at the holding spot. Try /dtp again."));
        return null;
    }

    /**
     * Whether a WAITING request has waited long enough. A train whose groups exist is
     * still coming up (chunk generation, Sable's first physics tick), so it gets the long
     * budget; with no group at all, nothing is coming.
     */
    static boolean waitTimedOut(int ticksWaited, boolean anyTrainGroupExists) {
        int limit = anyTrainGroupExists ? SPAWN_CAPTURE_TICKS : NO_TRAIN_TICKS;
        return ticksWaited >= limit;
    }

    /** Keep the waiting player at the holding spot instead of letting them fall while the train comes up. */
    private static void pinAtHoldingSpot(ServerPlayer player, PendingDtp pending) {
        if (Double.isNaN(pending.holdY())) return;
        if (player.getY() >= pending.holdY() - HOLD_SAG_BLOCKS) return;
        player.teleportTo(pending.trainLevel(), pending.targetX(), pending.holdY(), pending.holdZ(),
            player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    /** Log what each group of the train looks like when a wait times out, so a stuck train shows why. */
    private static void logWaitDiagnostics(PendingDtp pending, DungeonTrainWorldData data) {
        ServerLevel level = pending.trainLevel();
        BlockPos targetPos = BlockPos.containing(pending.targetX(), data.getTrainY(), 0);
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        LOGGER.warn("[DungeonTrain] /dtp wait diagnostics: level={} targetX={} targetChunkLoaded={} groups={} gameTime={}",
            level.dimension().location(), String.format("%.1f", pending.targetX()),
            level.isLoaded(targetPos), carriages.size(), level.getGameTime());
        carriages.stream().limit(MAX_DIAGNOSTIC_CARRIAGES).forEach(carriage -> {
            TrainTransformProvider p = carriage.provider();
            LOGGER.warn("[DungeonTrain] /dtp wait diagnostics:   pIdx={} groupSize={} canonicalPos={} physicsTicks={} spawnCaptureTick={} placed={}",
                p.getPIdx(), p.getGroupSize(), p.getCanonicalPos() == null ? "null" : "set",
                p.getPhysicsTickCounter(), p.getSpawnCaptureGameTick(), p.isPlacedSuccessfully());
        });
    }

    // ---- LANDING ----------------------------------------------------------------------------

    /** @return the next state, or {@code null} once the request is finished. */
    private static PendingDtp tickLanding(ServerPlayer player, PendingDtp pending, DungeonTrainWorldData data) {
        int ticks = pending.ticks() + 1;
        LandingStep step = decideLanding(player.getY(), deckTopY(data), ticks, pending.relands());
        switch (step) {
            case KEEP_WATCHING:
                return pending.withTicks(ticks);
            case LANDED:
                LOGGER.info("[DungeonTrain] /dtp landing confirmed for {} after {} re-landing(s)",
                    player.getName().getString(), pending.relands());
                return null;
            case RELAND:
                return reland(player, pending, data);
            case GIVE_UP:
            default:
                LOGGER.warn("[DungeonTrain] /dtp gave up keeping {} on the train after {} re-landings (y={})",
                    player.getName().getString(), pending.relands(), String.format("%.1f", player.getY()));
                player.sendSystemMessage(Component.literal(
                    "Couldn't keep you on the train. Try /dtp again."));
                return null;
        }
    }

    /**
     * One LANDING tick's decision. Falling below the deck is checked first, so a player who
     * drops on the last watched tick is still caught.
     */
    static LandingStep decideLanding(double playerY, double deckTopY, int ticksWatched, int relands) {
        if (hasFallen(playerY, deckTopY)) {
            return relands < MAX_RELANDS ? LandingStep.RELAND : LandingStep.GIVE_UP;
        }
        return ticksWatched >= LANDING_WATCH_TICKS ? LandingStep.LANDED : LandingStep.KEEP_WATCHING;
    }

    /** Whether a player at {@code playerY} has fallen off or through a deck whose top is {@code deckTopY}. */
    static boolean hasFallen(double playerY, double deckTopY) {
        return playerY < deckTopY - FALL_MARGIN_BLOCKS;
    }

    private static PendingDtp reland(ServerPlayer player, PendingDtp pending, DungeonTrainWorldData data) {
        int relands = pending.relands() + 1;
        LOGGER.warn("[DungeonTrain] /dtp {} fell off the train at y={} — re-landing ({} of {})",
            player.getName().getString(), String.format("%.1f", player.getY()), relands, MAX_RELANDS);
        PendingDtp counted = new PendingDtp(pending.trainLevel(), pending.targetX(), pending.holdY(),
            pending.holdZ(), Phase.LANDING, 0, relands);
        if (placeOnDeck(player, counted, data)) {
            return counted;
        }
        // The train is gone or unsettled again: back to the holding spot to wait for it.
        player.setInvulnerable(true);
        pinAtHoldingSpot(player, counted);
        return counted.waiting();
    }

    // ---- shared -----------------------------------------------------------------------------

    private static double deckTopY(DungeonTrainWorldData data) {
        return data.getTrainY() + 1.0;
    }

    /** @return true once the player was put on a settled deck; false to retry next tick. */
    private static boolean placeOnDeck(ServerPlayer player, PendingDtp pending, DungeonTrainWorldData data) {
        PlayerJoinEvents.FlatbedTarget flat = PlayerJoinEvents.findNearestFlatbedTarget(
            pending.trainLevel(), data, pending.targetX());
        if (flat == null) return false; // train not settled yet — retry next tick

        player.teleportTo(pending.trainLevel(), flat.x(), flat.y(), flat.z(), -90.0f, 0.0f);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        // The hold also carries the player forward with the train until Sable's collision reaches the client.
        double trainBlocksPerTick = DungeonTrainConfig.getSpeed() / 20.0;
        DungeonTrainNet.sendTo(player, new SpawnDeckHoldPacket(
            deckTopY(data), SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS, trainBlocksPerTick));
        player.setInvulnerable(false);
        player.sendSystemMessage(Component.literal(String.format(
            "Train ready — you're on the flatbed at (%.1f, %.1f, %.1f).", flat.x(), flat.y(), flat.z())));

        LOGGER.info("[DungeonTrain] /dtp placed {} on flatbed at ({}, {}, {})",
            player.getName().getString(),
            String.format("%.1f", flat.x()), String.format("%.1f", flat.y()), String.format("%.1f", flat.z()));
        return true;
    }
}
