package games.brennan.dungeontrain.wind;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.WindProfilePacket;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Server half of the train wind: holds the profile, the world's wind seed and the X where the wind starts,
 * syncs them to each client, and pushes the <b>non-player</b> entities that are out in the open on the train.
 * Players are pushed by their own client ({@code client.wind.WindClientEvents}) because player movement is
 * client-authoritative — both sides evaluate the same {@link WindField}, so they agree without a per-tick
 * packet.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WindServer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Dev runs read the profile straight from the source tree so the tuner's Save + reload is live. */
    private static final String SOURCE_PROFILE = "src/main/resources/data/dungeontrain/wind/profile.json";
    /** How far above a carriage's bounds wind still reaches (jumping, arrows in flight over the roof). */
    private static final double ABOVE_REACH = 4.0;
    private static final double SIDE_REACH = 1.0;

    /** Immutable snapshot of everything the wind needs. */
    public record State(WindProfile profile, int seed, long startX, boolean forcedOff) {
        public boolean active() {
            return !forcedOff && profile.enabled() && startX != Long.MIN_VALUE;
        }

        public WindSample sampleAt(double worldX, long tick) {
            if (!active()) return WindSample.CALM;
            return WindField.sample(profile, worldX - startX, tick, seed);
        }

        public WindProfilePacket packet() {
            return new WindProfilePacket(profile.toJson(), seed, startX, !forcedOff);
        }
    }

    private static volatile State state = new State(WindProfile.CALM, 0, Long.MIN_VALUE, false);
    private static volatile Long startOverride = null;
    /** DEBUG-log cadence for the push counter (headless Gate 2 evidence in debug.log). */
    private static final long REPORT_PERIOD = 200L;
    private static long pushedSinceReport = 0L;
    /** {@code debug wind drop} probes: entity → tick it was dropped. Logged every second for 10 s. */
    private static final java.util.Map<Entity, Long> PROBES = new java.util.WeakHashMap<>();
    private static final long PROBE_TICKS = 200L;

    private WindServer() {}

    public static State state() {
        return state;
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        reload(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        state = new State(WindProfile.CALM, 0, Long.MIN_VALUE, false);
        startOverride = null;
    }

    /** Re-read the profile and wind start, then resend to every player. Returns a one-line summary. */
    public static String reload(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        WindProfile profile = loadProfile();
        int seed = WindField.foldSeed(overworld.getSeed());
        Long override = startOverride;
        long startX = override != null ? override : windStartX(overworld);
        state = new State(profile, seed, startX, state.forcedOff());
        broadcast(server);
        String summary = "wind: " + profile.stages().size() + " stages, start X "
                + (startX == Long.MIN_VALUE ? "none (no Upside-down band)" : Long.toString(startX))
                + (override != null ? " (debug override)" : "")
                + (profile.enabled() ? "" : ", profile disabled");
        LOGGER.info("[DungeonTrain] {}", summary);
        return summary;
    }

    /** The {@code debug wind on|off} toggle (not saved; resets with the server). */
    public static void setForcedOff(MinecraftServer server, boolean off) {
        State s = state;
        state = new State(s.profile(), s.seed(), s.startX(), off);
        broadcast(server);
    }

    /**
     * The {@code debug wind start} override: move where the wind starts (e.g. to just behind the train at spawn,
     * to test without riding 20k+ blocks). {@code null} restores the real start. Not saved.
     */
    public static void overrideStart(MinecraftServer server, Long startX) {
        startOverride = startX;
        reload(server);
    }

    public static boolean startOverridden() {
        return startOverride != null;
    }

    /** World X where the first Upside-down stretch ends, or {@link Long#MIN_VALUE} when there is none. */
    static long windStartX(ServerLevel overworld) {
        if (UpsideDownBand.startX(overworld) == UpsideDownBand.OFF) return Long.MIN_VALUE;
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return Long.MIN_VALUE;
        return WorldGenCycle.fromConfig().upsideDownEndX();
    }

    static WindProfile loadProfile() {
        if (DungeonTrain.isDevBuild()) {
            Path gameDir = FMLPaths.GAMEDIR.get().toAbsolutePath();
            Path parent = gameDir.getParent();
            if (parent != null) {
                Path source = parent.resolve(SOURCE_PROFILE);
                if (Files.isRegularFile(source)) {
                    try {
                        return WindProfile.parse(Files.readString(source, StandardCharsets.UTF_8));
                    } catch (IOException | IllegalArgumentException e) {
                        LOGGER.warn("[DungeonTrain] wind: could not read {} ({}); using the bundled profile",
                                source, e.getMessage());
                    }
                }
            }
        }
        try (InputStream in = WindServer.class.getResourceAsStream(WindProfile.RESOURCE_PATH)) {
            if (in == null) {
                LOGGER.warn("[DungeonTrain] wind: {} missing from the jar; wind is off", WindProfile.RESOURCE_PATH);
                return WindProfile.CALM;
            }
            return WindProfile.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.error("[DungeonTrain] wind: bundled profile unreadable ({}); wind is off", e.getMessage());
            return WindProfile.CALM;
        }
    }

    private static void broadcast(MinecraftServer server) {
        WindProfilePacket packet = state.packet();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            DungeonTrainNet.sendTo(p, packet);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DungeonTrainNet.sendTo(player, state.packet());
        }
    }

    // ---- push -----------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        logProbes(level);
        State s = state;
        if (!s.active()) return;
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        if (carriages.isEmpty()) return;
        long tick = level.getGameTime();
        // Neighbouring carriages' boxes overlap at the couplings: push each entity once per tick.
        Set<Entity> done = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Trains.Carriage c : carriages) {
            pushAround(level, c.ship(), s, tick, done);
        }
        pushedSinceReport += done.size();
        if (tick % REPORT_PERIOD == 0) {
            LOGGER.debug("[DungeonTrain] wind: pushed {} entity-ticks over the last {} ticks; now {} at train",
                    pushedSinceReport, REPORT_PERIOD, done.size());
            pushedSinceReport = 0;
        }
    }

    private static void pushAround(ServerLevel level, ManagedShip ship, State s, long tick, Set<Entity> done) {
        AABBdc bb = ship.worldAABB();
        if (bb.maxX() < s.startX()) return; // whole carriage still before the wind
        if (!(ship instanceof SableManagedShip sable)) return;
        SubLevel sub = sable.subLevel();
        if (sub == null) return;
        AABB box = new AABB(bb.minX() - SIDE_REACH, bb.minY(), bb.minZ() - SIDE_REACH,
                bb.maxX() + SIDE_REACH, bb.maxY() + ABOVE_REACH, bb.maxZ() + SIDE_REACH);
        for (Entity e : level.getEntities((Entity) null, box, WindServer::pushable)) {
            if (done.contains(e)) continue;
            // Only entities that will actually move this tick: a non-ticking one would just bank velocity.
            if (!level.isPositionEntityTicking(e.blockPosition())) continue;
            WindSample w = s.sampleAt(e.getX(), tick);
            if (w.isCalm()) continue;
            if (!TrainExposure.isExposed(sub, e, w.dirX(), w.dirZ())) continue;
            done.add(e);
            WindPush.apply(e, w, s.profile(), 1.0);
        }
    }

    /** Track a {@code debug wind drop} probe: its position is logged at INFO every second for 10 s. */
    public static void trackProbe(Entity e, long tick) {
        synchronized (PROBES) {
            PROBES.put(e, tick);
        }
    }

    private static void logProbes(ServerLevel level) {
        long tick = level.getGameTime();
        synchronized (PROBES) {
            if (PROBES.isEmpty()) return;
            var it = PROBES.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                Entity e = entry.getKey();
                long age = tick - entry.getValue();
                if (!e.isAlive() || age > PROBE_TICKS) {
                    it.remove();
                    continue;
                }
                if (age % 20L == 0L) {
                    WindSample w = state.sampleAt(e.getX(), tick);
                    LOGGER.info("[DungeonTrain] wind probe {} t+{}s at X {} Y {} Z {} vel ({}, {}) wind {} toward {}°",
                            e.getType().toShortString(), age / 20L,
                            String.format("%.2f", e.getX()), String.format("%.2f", e.getY()),
                            String.format("%.2f", e.getZ()),
                            String.format("%.3f", e.getDeltaMovement().x), String.format("%.3f", e.getDeltaMovement().z),
                            String.format("%.4f", w.strength()), String.format("%.0f", Math.toDegrees(w.headingRad())));
                }
            }
        }
    }

    private static boolean pushable(Entity e) {
        return e.isAlive() && !(e instanceof Player) && !(e instanceof HangingEntity) && !(e instanceof Display)
                && !e.isPassenger() && !e.isNoGravity() && !e.isSpectator();
    }
}
