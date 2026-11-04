package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.wind.EndFrostbite;
import games.brennan.dungeontrain.wind.WindZones;
import games.brennan.dungeontrain.worldgen.CometShowerPlan;
import games.brennan.dungeontrain.worldgen.SpheresComets;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Comets over the spheres band ({@link SpheresComets} decides when and how often; {@link SpheresComet} flies
 * them). Each tick every train with a player near it rolls, at that player's X, for a comet shower
 * ({@link CometShowers}, which brings every strike on the train) or, while it has none on, a near miss that
 * streaks past the carriages around them. They explode on whatever they meet, a carriage or a sphere,
 * breaking blocks and setting fires; a carriage's lost blocks go through Sable's block-change hook like a
 * creeper's ({@code SableBlockChangeGuardMixin}), so the train is not shifted.
 *
 * <p>Overworld only. Not for a player in a dimensional carriage: that is somewhere else entirely.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SpheresCometEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Most comets in flight at once. */
    static final int MAX_LIVE = 6;
    /** A player this close (blocks, horizontally) to any of a train's carriages makes it a target. */
    private static final double WATCH_RANGE = 64.0;

    /** Comets in flight; server thread only. */
    private static List<SpheresComet> live = List.of();
    /** Next comet id, as the client knows it; server thread only. */
    private static int nextId;
    private SpheresCometEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;
        List<Trains.Carriage> carriages = live.isEmpty() ? null : Trains.allCarriages(level);
        if (carriages != null) live = stepAll(level, carriages);
        Map<UUID, List<Trains.Carriage>> trains = Trains.byTrainId(level);
        CometShowers.tick(level, trains);
        for (Map.Entry<UUID, List<Trains.Carriage>> entry : trains.entrySet()) {
            if (CometShowers.isOn(entry.getKey())) continue;
            List<Trains.Carriage> train = entry.getValue();
            ServerPlayer watcher = watcher(level, train);
            if (watcher == null) continue;
            int x = (int) Math.floor(watcher.getX());
            long lap = lapAt(x);
            double endSky = WindZones.endSkyAt(x), spheresVoid = WindZones.spheresVoidAt(x);
            if (!SpheresComets.active(lap, endSky, spheresVoid)) continue;
            double depth = depthAt(x);
            CometShowerPlan plan = CometShowerPlan.of(lap, depth);
            if (level.random.nextDouble() < plan.startChancePerTick()) {
                CometShowers.start(level, entry.getKey(), train, watcher, plan);
            } else if (live.size() < MAX_LIVE && level.random.nextDouble()
                    < SpheresComets.nearMissChancePerTick(lap, endSky, spheresVoid, depth)) {
                launch(level, train, watcher.position(), Aim.MISS);
            }
        }
    }

    /**
     * Start a shower over {@code watcher}'s part of the train, from the plan where they are, whatever the band.
     * @return true if one started (false if the train already has one)
     */
    public static boolean startShower(ServerLevel level, UUID trainId, List<Trains.Carriage> train,
                                      ServerPlayer watcher) {
        int x = (int) Math.floor(watcher.getX());
        return CometShowers.start(level, trainId, train, watcher, CometShowerPlan.of(lapAt(x), depthAt(x)));
    }

    /** Fly one of a shower's strikes into the carriages near {@code focus}, along its {@code approach}. */
    @Nullable
    static SpheresComet launchStrike(ServerLevel level, List<Trains.Carriage> train, Vec3 focus, Vec3 approach,
                                     double speedScale) {
        SpheresComet comet = SpheresComet.launch(level, train, focus, Aim.HIT, approach, speedScale, nextId++,
            level.random);
        if (comet != null) add(comet);
        return comet;
    }

    /** Whether comet {@code id} is still in the air. */
    static boolean isLive(int id) {
        for (SpheresComet c : live) {
            if (c.id() == id) return true;
        }
        return false;
    }

    /** Forget comets and showers, so none carries into the next world opened this session. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        live = List.of();
        CometShowers.clear();
    }

    /** Throw away a comet display left in a save from before comets were drawn by the client. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity().getTags().contains(SpheresComet.TAG)) event.setCanceled(true);
    }

    /** Where a comet is sent: into the train, past it (beside, over or ahead, at random), or past it ahead. */
    public enum Aim { HIT, MISS, MISS_AHEAD }

    /**
     * Launch a comet at the part of {@code train} near {@code focus} (a watching player), whatever the band.
     * @return true if one was launched
     */
    public static boolean launch(ServerLevel level, List<Trains.Carriage> train, Vec3 focus, Aim aim) {
        SpheresComet comet = SpheresComet.launch(level, train, focus, aim, nextId++, level.random);
        if (comet == null) return false;
        add(comet);
        LOGGER.debug("[DungeonTrain] Comet launched {} at {} ({})", comet.id(), comet.pos(), aim);
        return true;
    }

    private static void add(SpheresComet comet) {
        List<SpheresComet> next = new ArrayList<>(live);
        next.add(comet);
        live = List.copyOf(next);
    }

    /** Comets now in flight. */
    public static int liveCount() {
        return live.size();
    }

    /** Chance per tick of a comet for a train whose lead carriage is at {@code x}. */
    public static double chanceAt(int x) {
        long lap = lapAt(x);
        return SpheresComets.chancePerTick(lap, WindZones.endSkyAt(x), WindZones.spheresVoidAt(x));
    }

    /** How far through the spheres band's End-sky stretch world X is, 0..1 (where lap 0's comets fall). */
    private static double depthAt(int x) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        long end = cycle.spheresLen() - Math.max(0, SpheresProgressionConfig.exitVoidBlocks());
        return SpheresComets.firstLapDepth(cycle.spheresCoreOffset(x),
            SpheresProgressionConfig.segments().endSkyStart(), end);
    }

    /** The lap (0-based) of the cycle at world X. */
    private static long lapAt(int x) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        return cycle.cycleIndex(EndFrostbite.frostX(cycle, x));
    }

    private static List<SpheresComet> stepAll(ServerLevel level, List<Trains.Carriage> carriages) {
        List<SpheresComet> next = new ArrayList<>(live.size());
        for (SpheresComet comet : live) {
            comet.step(level, carriages).ifPresent(next::add);
        }
        return List.copyOf(next);
    }

    /**
     * A random player near {@code train} (not spectating, not in a dimensional carriage), or null. The train can be
     * hundreds of blocks long, so "near" is near any of its carriages, and the comet then comes down by them.
     */
    private static ServerPlayer watcher(ServerLevel level, List<Trains.Carriage> train) {
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            if (PortalTwinSpace.isInside(level, p.getBlockX(), p.getY())) continue;
            if (nearAny(train, p.getX(), p.getZ())) near.add(p);
        }
        return near.isEmpty() ? null : near.get(level.random.nextInt(near.size()));
    }

    private static boolean nearAny(List<Trains.Carriage> train, double x, double z) {
        for (Trains.Carriage c : train) {
            AABBdc bb = c.ship().worldAABB();
            double dx = Math.max(0.0, Math.max(bb.minX() - x, x - bb.maxX()));
            double dz = Math.max(0.0, Math.max(bb.minZ() - z, z - bb.maxZ()));
            if (dx * dx + dz * dz <= WATCH_RANGE * WATCH_RANGE) return true;
        }
        return false;
    }
}
