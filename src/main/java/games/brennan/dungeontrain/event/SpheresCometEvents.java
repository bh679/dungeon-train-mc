package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.wind.EndFrostbite;
import games.brennan.dungeontrain.wind.WindZones;
import games.brennan.dungeontrain.worldgen.SpheresComets;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Comets over the spheres band ({@link SpheresComets} decides when and how often; {@link SpheresComet} flies
 * them). Each tick every train with a player near it rolls for a comet at its lead carriage's X; about a third
 * are aimed into the train, the rest streak past. They explode on whatever they meet — a carriage or a sphere —
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
    /** A player this close (blocks, horizontally) to a train's lead carriage makes it a target. */
    private static final double WATCH_RANGE = 160.0;

    /** Comets in flight; server thread only. */
    private static List<SpheresComet> live = List.of();

    private SpheresCometEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;
        List<Trains.Carriage> carriages = live.isEmpty() ? null : Trains.allCarriages(level);
        if (carriages != null) live = stepAll(level, carriages);
        if (live.size() >= MAX_LIVE) return;
        for (List<Trains.Carriage> train : Trains.byTrainId(level).values()) {
            if (train.isEmpty() || !watched(level, train)) continue;
            int x = (int) Math.floor(centreX(Trains.lead(train)));
            double chance = chanceAt(x);
            if (chance <= 0.0 || level.random.nextDouble() >= chance) continue;
            launch(level, train, level.random.nextDouble() < SpheresComets.AIMED_SHARE);
            if (live.size() >= MAX_LIVE) return;
        }
    }

    /** Throw away a comet display left in a save by a server stopped mid-flight. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || !event.getEntity().getTags().contains(SpheresComet.TAG)) return;
        UUID id = event.getEntity().getUUID();
        if (live.stream().noneMatch(c -> c.displayId().equals(id))) event.setCanceled(true);
    }

    /**
     * Launch a comet at {@code train} now, whatever the band (the debug command's path).
     * @return true if one was launched
     */
    public static boolean launch(ServerLevel level, List<Trains.Carriage> train, boolean aimed) {
        SpheresComet comet = SpheresComet.launch(level, train, aimed, level.random);
        if (comet == null) return false;
        List<SpheresComet> next = new ArrayList<>(live);
        next.add(comet);
        live = List.copyOf(next);
        LOGGER.debug("[DungeonTrain] Comet launched {} at {} (aimed={})", comet.displayId(), comet.pos(), aimed);
        return true;
    }

    /** Comets now in flight. */
    public static int liveCount() {
        return live.size();
    }

    /** Chance per tick of a comet for a train whose lead carriage is at {@code x}. */
    public static double chanceAt(int x) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        long lap = cycle.cycleIndex(EndFrostbite.frostX(cycle, x));
        return SpheresComets.chancePerTick(lap, WindZones.endSkyAt(x), WindZones.spheresVoidAt(x));
    }

    private static List<SpheresComet> stepAll(ServerLevel level, List<Trains.Carriage> carriages) {
        List<SpheresComet> next = new ArrayList<>(live.size());
        for (SpheresComet comet : live) {
            comet.step(level, carriages).ifPresent(next::add);
        }
        return List.copyOf(next);
    }

    /** A player (not spectating, not in a dimensional carriage) is near this train. */
    private static boolean watched(ServerLevel level, List<Trains.Carriage> train) {
        AABBdc bb = Trains.lead(train).ship().worldAABB();
        Vec3 centre = new Vec3((bb.minX() + bb.maxX()) / 2, (bb.minY() + bb.maxY()) / 2, (bb.minZ() + bb.maxZ()) / 2);
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (PortalTwinSpace.isInside(level, p.getBlockX(), p.getY())) continue;
            double dx = p.getX() - centre.x, dz = p.getZ() - centre.z;
            if (dx * dx + dz * dz <= WATCH_RANGE * WATCH_RANGE) return true;
        }
        return false;
    }

    private static double centreX(Trains.Carriage carriage) {
        AABBdc bb = carriage.ship().worldAABB();
        return (bb.minX() + bb.maxX()) / 2;
    }
}
