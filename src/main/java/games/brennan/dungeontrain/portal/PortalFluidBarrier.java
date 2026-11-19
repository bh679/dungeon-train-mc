package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.PortalCarriageEvents;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Water may not cross the boundary of a dimensional carriage, in either direction.
 *
 * <p><b>Why.</b> Rooms stand in the empty basement under the world, a lane every few blocks, and an
 * Endless Open room leaves its edges open on purpose — {@code lowkeyabridge} and {@code statues} carry
 * water right up to them, and an author's room can carry anything. Water reaching an open edge poured
 * out into the basement, fell, and landed on whatever stood below: a hall like {@code ender}, a floor
 * and a roof with no side walls, took it straight in at its own open edge. A pair's base room is never
 * retired and the registry is in memory only, so after a restart every room a world ever built is
 * still down there leaking, with nothing tracking it.</p>
 *
 * <p><b>What.</b> A flow is refused when its two cells are not in the same structure: leaving a room,
 * entering one, or passing from one to another. Inside a room nothing changes — a bucket, a fountain,
 * an authored river all flow as before; an Endless Open water plane just stops at the window edge,
 * behind the fog. "In a structure" is a standing copy of the room (any tile, over the room's own rows)
 * or one of its corridors, as {@link PortalCarriageBuilder#allCorridorMask} places them.</p>
 *
 * <p>Read by {@code FlowingFluidPortalRoomMixin}, on {@code FlowingFluid#canSpreadTo}, which runs for
 * every spreading fluid in the world. Hence the snapshot: built at most once a tick and only when a
 * fluid asks, each structure's geometry cached against the (immutable) record itself, and one box
 * around them all so a flow anywhere else — every sea and river above the bedrock — is answered by a
 * single comparison.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalFluidBarrier {

    /** Snapshot of the structures standing this tick, or null before anything asked. */
    private static Snapshot snapshot;
    /**
     * Per-structure geometry, kept while the record is current. A structure that changes is a new
     * record and so a new key; the old one drops out at the next rebuild.
     */
    private static Map<PortalStructure, Room> rooms = new IdentityHashMap<>();

    private PortalFluidBarrier() {}

    /**
     * True when a fluid at {@code from} must not spread to {@code to}. Overworld only, server thread
     * only — rooms are stamped nowhere else, and the registry is not safe to read from another thread.
     */
    public static boolean blocksFlow(ServerLevel level, BlockPos from, BlockPos to) {
        if (level.dimension() != Level.OVERWORLD) return false;
        if (!level.getServer().isSameThread()) return false;
        Snapshot rooms = snapshotFor(level);
        return !rooms.sameStructure(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
    }

    /** The snapshot is keyed on game time, which a new world in the same JVM can repeat. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        reset();
    }

    /** Forget everything, for a server stopping or a test. */
    public static void reset() {
        snapshot = null;
        rooms = new IdentityHashMap<>();
    }

    private static Snapshot snapshotFor(ServerLevel level) {
        long tick = level.getGameTime();
        if (snapshot != null && snapshot.tick == tick) return snapshot;
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        Map<PortalStructure, Room> next = new IdentityHashMap<>();
        for (PortalStructure structure : standingStructures()) {
            Room known = rooms.get(structure);
            next.put(structure, known != null ? known : Room.of(level, structure, dims));
        }
        rooms = next;
        snapshot = new Snapshot(tick, new ArrayList<>(next.values()));
        return snapshot;
    }

    /** The live pairs, and the room each editor test is standing up. */
    private static List<PortalStructure> standingStructures() {
        Collection<PortalStructure> live = PortalCarriageEvents.liveStructures();
        List<PortalStructure> standing = new ArrayList<>(live);
        for (Map.Entry<UUID, PortalTestSession.Session> trip : PortalTestSession.entries()) {
            standing.add(trip.getValue().structure());
        }
        return standing;
    }

    /** One tick's structures, and the box around all of them. */
    static final class Snapshot {
        final long tick;
        private final List<Room> rooms;
        private final BoundingBox all;

        Snapshot(long tick, List<Room> rooms) {
            this.tick = tick;
            this.rooms = rooms;
            this.all = rooms.isEmpty() ? null
                : BoundingBox.encapsulatingBoxes(rooms.stream().map(Room::bounds).toList()).orElse(null);
        }

        /** True when both cells are in the same structure, or both are in none. */
        boolean sameStructure(int fx, int fy, int fz, int tx, int ty, int tz) {
            if (all == null) return true;
            boolean fromNear = all.isInside(fx, fy, fz);
            boolean toNear = all.isInside(tx, ty, tz);
            if (!fromNear && !toNear) return true;
            return containing(fx, fy, fz) == containing(tx, ty, tz);
        }

        private Room containing(int x, int y, int z) {
            for (Room room : rooms) {
                if (room.contains(x, y, z)) return room;
            }
            return null;
        }
    }

    /**
     * What one structure occupies, as far as water is concerned: its standing copies over the room's
     * rows, and its corridors.
     */
    record Room(BoundingBox bounds, int roomX, int roomY, int roomZ, Vec3i roomSize,
                PortalRoomTiling tiling, PortalCorridorMask corridors) {

        static Room of(ServerLevel level, PortalStructure structure, CarriageDims dims) {
            PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
            BlockPos room = structure.roomOrigin(dims, layout);
            return new Room(PortalCarriageBuilder.footprintOf(level, structure, dims),
                room.getX(), room.getY(), room.getZ(), structure.roomSize(), structure.tiling(),
                PortalCarriageBuilder.allCorridorMask(structure, dims));
        }

        boolean contains(int x, int y, int z) {
            if (!bounds.isInside(x, y, z)) return false;
            if (corridors.covers(x, y, z)) return true;
            if (y < roomY || y >= roomY + roomSize.getY()) return false;
            return tiling.has(new PortalRoomTiling.Tile(
                Math.floorDiv(x - roomX, roomSize.getX()),
                Math.floorDiv(z - roomZ, roomSize.getZ())));
        }
    }
}
