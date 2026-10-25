package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
import games.brennan.dungeontrain.train.CarriageDims;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds only a dimensional carriage's own chunks while a player is inside one, instead of the player's
 * whole view distance.
 *
 * <h2>The problem</h2>
 * <p>A room stands in twin space, the sealed basement under the overworld's bedrock (or the attic over
 * a lid), and a crossing is a real teleport, so the player genuinely stands at the room's X/Z.
 * Vanilla's player tickets ignore height: the server loaded, and generated to completion, every
 * overworld column within view distance of that point. The twin stays put while the train rolls on and
 * endless copies follow the player wherever they walk, so walking the rooms generated land the train
 * never went near, all of it sealed overhead where the player could never see it.</p>
 *
 * <h2>The switch</h2>
 * <p>Vanilla already has one: {@code ChunkMap#skipPlayer}, which is how a spectator with
 * {@code spectatorsGenerateChunks} off adds no tickets yet still receives every chunk that is loaded.
 * {@code ChunkMapTwinSpaceSkipMixin} answers yes there for a held player, and {@code ChunkMap#move}
 * swaps the player's view-distance tickets out and back in as that answer flips. In their place this
 * class adds two kinds of its own:</p>
 * <ul>
 *   <li><b>Around the player</b>, {@link PortalTwinChunkReach#chunkRadius} chunks: where the tiler
 *       will stamp next and where the room's mobs need to keep ticking.</li>
 *   <li><b>Under every copy still standing</b>, chunk by chunk, for any structure with copies out or
 *       a held occupant. The tiler erases a copy that falls out of the window by writing air through
 *       the level, and a write into an unloaded chunk is a synchronous load on the server tick. View
 *       distance happened to cover those copies; the radius above does not have to.</li>
 * </ul>
 *
 * <h2>Order</h2>
 * <p>A player is marked held only once their ticket is down, and {@link #isHeld} also asks whether they
 * are still in twin space, so leaving flips them back to vanilla tickets on the very next move while
 * ours is still alive. Each flip is pushed through {@code ServerChunkCache#move} straight away rather
 * than waiting for the player to step.</p>
 */
public final class PortalTwinChunkHold {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long one of our tickets outlives its last refresh, in ticks. */
    private static final int TICKET_LIFETIME = 60;

    /** How often a standing ticket is restated, well inside {@link #TICKET_LIFETIME}. */
    private static final int REFRESH_TICKS = 20;

    /** Around a held player. Keyed by chunk, so a refresh restates the same ticket. */
    private static final TicketType<ChunkPos> ROOM_TICKET =
        TicketType.create("dungeontrain_twin_room", Comparator.comparingLong(ChunkPos::toLong), TICKET_LIFETIME);

    /** Under a standing copy. Radius 0: loaded to FULL so the tiler's erase never forces a load. */
    private static final TicketType<ChunkPos> RESIDENT_TICKET =
        TicketType.create("dungeontrain_twin_resident", Comparator.comparingLong(ChunkPos::toLong), TICKET_LIFETIME);

    /** Held players and the dimension they are held in. Read from {@code ChunkMap}, hence concurrent. */
    private static final Map<UUID, ResourceKey<Level>> HELD = new ConcurrentHashMap<>();

    /** The last ticket each held player was given. */
    private record Placed(long chunk, int radius, long at) {}

    private static final Map<UUID, Placed> PLACED = new HashMap<>();

    /** Game time the resident tickets were last restated, per dimension. */
    private static final Map<ResourceKey<Level>, Long> RESIDENT_AT = new HashMap<>();

    private PortalTwinChunkHold() {}

    /** Whether vanilla should skip this player's view-distance tickets: held, and still in twin space. */
    public static boolean isHeld(ServerPlayer player) {
        if (HELD.isEmpty()) return false;
        ResourceKey<Level> dim = HELD.get(player.getUUID());
        if (dim == null || !dim.equals(player.level().dimension())) return false;
        return PortalTwinSpace.isInside(player.serverLevel(), player.getBlockX(), player.getY());
    }

    /** The ticket radius a player in {@code structure} needs, in chunks. */
    public static int roomRadius(ServerLevel level, CarriageDims dims, PortalStructure structure) {
        int exitReach = PortalExitSites.tileReach(
            PortalCorridorSize.corridorLength(dims, structure.kind()), structure.roomLength(),
            PortalCarriageBuilder.plugDepth());
        int reach = PortalTwinChunkReach.reachBlocks(structure.mode().tiles(), structure.roomLength(),
            structure.roomWidth(), structure.tileBudget(1), exitReach, structure.spanX(dims));
        return PortalTwinChunkReach.chunkRadius(reach, level.getServer().getPlayerList().getViewDistance());
    }

    /**
     * One tick for {@code level}.
     *
     * @param players    the level's players this pass is about (portal testers already left out)
     * @param roomRadii  player → {@link #roomRadius} of the room they stand in; absent = in no room
     * @param structures every live structure in this level
     * @param occupied   the structures somebody is standing in
     */
    public static void tick(ServerLevel level, CarriageDims dims, List<ServerPlayer> players,
                            Map<UUID, Integer> roomRadii, Collection<PortalStructure> structures,
                            Collection<PortalStructure> occupied) {
        ResourceKey<Level> dim = level.dimension();
        if (!DungeonTrainCommonConfig.isTwinSpaceRoomOnlyChunks()) {
            releaseAll(level, dim);
            return;
        }
        long now = level.getGameTime();
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer player : players) {
            if (player.isSpectator() || player.isRemoved()) continue;
            if (!PortalTwinSpace.isInside(level, player.getBlockX(), player.getY())) continue;
            UUID id = player.getUUID();
            seen.add(id);
            hold(level, player, roomRadii.getOrDefault(id, PortalTwinChunkReach.MIN_RADIUS), now);
        }
        releaseUnseen(level, dim, seen);
        // Only while somebody here is held: with nobody held, every copy is under some player's own
        // view distance exactly as it always was, and holding them anyway would change what a world
        // with nobody in a room keeps loaded.
        if (!seen.isEmpty() && now - lastResidentRefresh(dim) >= REFRESH_TICKS) {
            holdResidents(level, dims, structures, occupied);
            RESIDENT_AT.put(dim, now);
        }
    }

    private static void hold(ServerLevel level, ServerPlayer player, int radius, long now) {
        UUID id = player.getUUID();
        ChunkPos chunk = player.chunkPosition();
        Placed last = PLACED.get(id);
        boolean stale = last == null || last.chunk() != chunk.toLong() || last.radius() != radius
            || now - last.at() >= REFRESH_TICKS;
        if (stale) {
            level.getChunkSource().addRegionTicket(ROOM_TICKET, chunk, radius, chunk);
            PLACED.put(id, new Placed(chunk.toLong(), radius, now));
        }
        if (HELD.put(id, level.dimension()) == null) {
            LOGGER.debug("[DT-TwinChunks] {} held at chunk {} radius {} (view distance {})",
                player.getGameProfile().getName(), chunk, radius,
                level.getServer().getPlayerList().getViewDistance());
            // Flip now: the ticket is down, so the view-distance tickets can go this tick.
            level.getChunkSource().move(player);
        }
    }

    private static void releaseUnseen(ServerLevel level, ResourceKey<Level> dim, Set<UUID> seen) {
        HELD.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(dim) || seen.contains(entry.getKey())) return false;
            PLACED.remove(entry.getKey());
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.level() == level) {
                LOGGER.debug("[DT-TwinChunks] {} released", player.getGameProfile().getName());
                // Back to vanilla tickets this tick, not on their next step.
                level.getChunkSource().move(player);
            }
            return true;
        });
    }

    /**
     * Hand every player held in {@code level} back to vanilla loading — for a tick that will not run
     * {@link #tick}, so nothing would restate their tickets before they lapse.
     */
    public static void releaseLevel(ServerLevel level) {
        releaseAll(level, level.dimension());
    }

    /** Forget everything; the next world's players and pairs are not these. */
    public static void clear() {
        HELD.clear();
        PLACED.clear();
        RESIDENT_AT.clear();
    }

    private static void releaseAll(ServerLevel level, ResourceKey<Level> dim) {
        if (!HELD.isEmpty()) releaseUnseen(level, dim, Set.of());
        RESIDENT_AT.remove(dim);
    }

    private static long lastResidentRefresh(ResourceKey<Level> dim) {
        Long at = RESIDENT_AT.get(dim);
        return at == null ? Long.MIN_VALUE / 2 : at;
    }

    /**
     * A radius-0 ticket on every chunk a standing copy, extra corridor or occupied base structure
     * touches. Structures with nothing standing beyond their base and nobody in them are left to
     * whatever else loads them, as before. A held player who walked on keeps the copies behind
     * them loaded until the tiler has taken them down; another room's copies are held too while
     * anybody is, which costs a few chunks and is simpler than telling the rooms apart.
     */
    private static void holdResidents(ServerLevel level, CarriageDims dims,
                                      Collection<PortalStructure> structures,
                                      Collection<PortalStructure> occupied) {
        LongSet chunks = new LongOpenHashSet();
        for (PortalStructure structure : structures) {
            boolean spread = !structure.tiling().isBaseOnly() || !structure.exitCopies().isEmpty();
            if (!spread && !occupied.contains(structure)) continue;
            addResidentChunks(dims, structure, chunks);
        }
        for (long packed : chunks) {
            ChunkPos pos = new ChunkPos(packed);
            level.getChunkSource().addRegionTicket(RESIDENT_TICKET, pos, 0, pos);
        }
    }

    private static void addResidentChunks(CarriageDims dims, PortalStructure structure, LongSet out) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        Vec3i room = structure.roomSize();
        for (Tile tile : structure.tiling().tiles()) {
            BlockPos origin = structure.tileOrigin(dims, layout, tile);
            addChunks(origin.getX(), origin.getZ(),
                origin.getX() + room.getX() - 1, origin.getZ() + room.getZ() - 1, out);
        }
        for (Site site : structure.exitCopies().sites()) {
            BoundingBox box = PortalExitCopyTiler.copyBox(structure, dims, site);
            if (box != null) addChunks(box.minX(), box.minZ(), box.maxX(), box.maxZ(), out);
        }
        // The base pair's corridors and the plug past them: the walk back out to the carriage.
        BlockPos origin = structure.origin();
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        int plug = PortalCarriageBuilder.plugDepth();
        addChunks(origin.getX() - plug, Math.min(origin.getZ(), roomOrigin.getZ()) - 1,
            origin.getX() + structure.spanX(dims) + plug,
            Math.max(origin.getZ() + dims.width(), roomOrigin.getZ() + room.getZ()), out);
    }

    private static void addChunks(int minX, int minZ, int maxX, int maxZ, LongSet out) {
        int cx0 = SectionPos.blockToSectionCoord(minX);
        int cx1 = SectionPos.blockToSectionCoord(maxX);
        int cz0 = SectionPos.blockToSectionCoord(minZ);
        int cz1 = SectionPos.blockToSectionCoord(maxZ);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) out.add(ChunkPos.asLong(cx, cz));
        }
    }
}
