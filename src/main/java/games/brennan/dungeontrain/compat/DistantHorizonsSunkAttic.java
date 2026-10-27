package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.portal.PortalTwinRegion;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SunkZone;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.preset.AmplifiedDrop;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * Which blocks <b>Distant Horizons</b> must leave out of its LODs so the sunk zone's attic never shows in
 * its horizon. Names no DH type — {@link DistantHorizonsAtticFilter} is the DH-facing half.
 *
 * <p><b>Why only the sunk zone.</b> Everywhere else a dimensional carriage's twin is stamped in the sealed
 * basement under the bedrock, behind solid ground that DH draws over it. In the {@link SunkZone} (the
 * {@code ow:sunk} approach after the spheres, and Amplified) the terrain sank into that basement, so the
 * twins live in an attic over a <em>barrier</em> lid instead — invisible, and skipped by DH's default
 * ignored-block list. Inside render distance {@code SunkLidSkyPlane} hides the attic; past it DH drew the
 * rooms hanging in the sky. Leaving the attic out of DH's data keeps the valleys' horizon and drops only
 * the rooms.</p>
 *
 * <p><b>Threading.</b> DH asks from its own worker threads, concurrently. Nothing here touches world
 * state off-thread: a server level answers from a snapshot the server thread refreshes, a client level
 * from the client's already-synced band state through {@link #setClientResolver}.</p>
 */
public final class DistantHorizonsSunkAttic {

    /** Chunk width, in blocks. */
    private static final int CHUNK = 16;
    /** Server ticks between snapshot refreshes: {@code startsWithTrain} only changes at world setup. */
    private static final int REFRESH_TICKS = 20;

    /** The overworld's attic as the server thread last saw it, or {@code null} when it has none. */
    private record ServerSnapshot(ServerLevel overworld, PortalTwinRegion attic) {}

    private static volatile ServerSnapshot serverSnapshot = null;
    /** Answers for a client-side level (a DH client on a server without DH); set by the client setup. */
    private static volatile BiFunction<Object, Integer, PortalTwinRegion> clientResolver = (level, x) -> null;
    /** The player's toggle; set by the client setup. A dedicated server has no client config: always on. */
    private static volatile BooleanSupplier enabled = () -> true;
    private static int ticksToRefresh = 0;

    private DistantHorizonsSunkAttic() {}

    /** Keep the server snapshot current. Call once, only when DH is loaded. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(DistantHorizonsSunkAttic::onServerTick);
        NeoForge.EVENT_BUS.addListener(DistantHorizonsSunkAttic::onServerStopped);
    }

    public static void setClientResolver(BiFunction<Object, Integer, PortalTwinRegion> resolver) {
        clientResolver = resolver;
    }

    public static void setEnabled(BooleanSupplier toggle) {
        enabled = toggle;
    }

    /**
     * The attic DH must drop from chunk column {@code chunkX} of {@code level} (the object DH's level
     * wrapper wraps), or {@code null} to store the chunk as it is. A chunk is filtered when either edge
     * column is sunk, so a twin straddling the zone's edge is never half drawn; above the lid outside the
     * zone there is nothing but sky to lose.
     */
    public static PortalTwinRegion atticForChunk(Object level, int chunkX) {
        if (!enabled.getAsBoolean()) return null;
        int minX = chunkX * CHUNK;
        int maxX = minX + CHUNK - 1;
        if (level instanceof ServerLevel server) {
            ServerSnapshot snap = serverSnapshot;
            if (snap == null || snap.overworld() != server) return null;
            WorldGenCycle cycle = WorldGenCycle.fromConfig();
            return SunkZone.contains(cycle, minX) || SunkZone.contains(cycle, maxX) ? snap.attic() : null;
        }
        PortalTwinRegion attic = clientResolver.apply(level, minX);
        return attic != null ? attic : clientResolver.apply(level, maxX);
    }

    /** Whether a block at {@code y} inside {@code attic} is left out. {@code null} keeps everything. */
    public static boolean hides(PortalTwinRegion attic, int y) {
        return attic != null && attic.contains(y);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (--ticksToRefresh > 0) return;
        ticksToRefresh = REFRESH_TICKS;
        ServerLevel overworld = event.getServer().getLevel(Level.OVERWORLD);
        serverSnapshot = overworld == null ? null : snapshot(overworld);
    }

    private static ServerSnapshot snapshot(ServerLevel overworld) {
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return null;
        AmplifiedDrop drop = AmplifiedDrop.of(overworld);
        if (!drop.active()) return null;
        return new ServerSnapshot(overworld, PortalTwinSpace.amplifiedAttic(drop, overworld.getMaxBuildHeight()));
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        serverSnapshot = null;
        ticksToRefresh = 0;
    }
}
