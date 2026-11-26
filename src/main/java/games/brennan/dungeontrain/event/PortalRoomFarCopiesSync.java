package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.net.PortalRoomFarCopiesPacket;
import games.brennan.dungeontrain.net.PortalRoomFarCopiesPacket.Box;
import games.brennan.dungeontrain.net.PortalRoomFarCopiesPacket.TileRef;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.portal.PortalRoomTiling;
import games.brennan.dungeontrain.portal.PortalStructure;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sends {@link PortalRoomFarCopiesPacket} to whoever stands in an endless room — the server half of
 * drawing that room's copies out to the Distant Horizons horizon.
 *
 * <p>Same shape as the fog sender in {@link PortalCarriageEvents}, on purpose: deduped per player
 * against the last packet sent, so nothing goes out while the answer is unchanged, and cleared with
 * {@code none()} when the player is in no endless room any more. It is sent whether or not the
 * player runs Distant Horizons; the server cannot know, and the client ignores it without DH.</p>
 */
final class PortalRoomFarCopiesSync {

    /**
     * How far from the room another pair's structure still matters, in blocks. Past DH's own
     * render distance at its largest setting nothing is drawn anyway.
     */
    private static final int BLOCKED_REACH = 8192;

    /** Most neighbours listed. Pairs are spaced along the train, so the nearest few are the ones in reach. */
    private static final int MAX_NEIGHBOURS = 64;

    private static final Map<UUID, PortalRoomFarCopiesPacket> LAST = new HashMap<>();

    private PortalRoomFarCopiesSync() {}

    /**
     * Send the room's far-copies description to every player inside {@code box} (the same padded
     * box the fog is delivered on) whose last packet differs.
     */
    static void sendFor(ServerLevel level, List<ServerPlayer> players, CarriageDims dims,
                        PortalCarriageLayout layout, PortalStructure structure,
                        Collection<PortalStructure> others, AABB box, Set<UUID> sent) {
        PortalRoomMode mode = structure.mode();
        if (!mode.tiles()) return;

        PortalRoomFarCopiesPacket packet = null;
        for (ServerPlayer player : players) {
            if (!box.contains(player.getX(), player.getY(), player.getZ())) continue;
            sent.add(player.getUUID());
            if (packet == null) packet = describe(level, dims, layout, structure, others);
            if (packet.equals(LAST.get(player.getUUID()))) continue;
            LAST.put(player.getUUID(), packet);
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    /** Take the far copies away from anyone who had them and is no longer in an endless room. */
    static void clearFor(List<ServerPlayer> players, Set<UUID> stillInside) {
        if (LAST.isEmpty()) return;
        List<UUID> present = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            present.add(id);
            if (stillInside.contains(id) || !LAST.containsKey(id)) continue;
            LAST.remove(id);
            PacketDistributor.sendToPlayer(player, PortalRoomFarCopiesPacket.none());
        }
        // A player who left the world never hears it; the client drops the region on logout anyway.
        LAST.keySet().removeIf(id -> !present.contains(id) && !stillInside.contains(id));
    }

    static PortalRoomFarCopiesPacket describe(ServerLevel level, CarriageDims dims,
                                              PortalCarriageLayout layout, PortalStructure structure,
                                              Collection<PortalStructure> others) {
        BlockPos room = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();
        int mode = structure.mode() == PortalRoomMode.ENDLESS_OPEN
            ? PortalRoomFarCopiesPacket.MODE_OPEN
            : PortalRoomFarCopiesPacket.MODE_REPETITION;

        List<TileRef> stamped = new ArrayList<>(structure.tiling().size());
        for (PortalRoomTiling.Tile tile : structure.tiling().tiles()) {
            stamped.add(new TileRef(tile.x(), tile.z()));
        }
        // Sorted so two tilings holding the same tiles compare equal and dedupe.
        stamped.sort(Comparator.comparingInt(TileRef::x).thenComparingInt(TileRef::z));

        return new PortalRoomFarCopiesPacket(mode,
            room.getX(), room.getY(), room.getZ(),
            size.getX(), size.getY(), size.getZ(),
            structure.tiledMinX(dims, layout), room.getY(), structure.tiledMinZ(dims, layout),
            structure.tiledMaxX(dims, layout), room.getY() + size.getY(), structure.tiledMaxZ(dims, layout),
            stamped, blocked(level, dims, structure, others, room, size));
    }

    /**
     * Where no far copy may be drawn: this pair's own corridors, and every other pair's structure
     * that shares the room's height and is in reach.
     */
    private static List<Box> blocked(ServerLevel level, CarriageDims dims, PortalStructure structure,
                                     Collection<PortalStructure> others, BlockPos room, Vec3i size) {
        List<Box> boxes = new ArrayList<>();
        for (BoundingBox corridor : PortalCarriageBuilder.allCorridorMask(structure, dims).boxes()) {
            boxes.add(toBox(corridor));
        }

        int minY = room.getY();
        int maxY = room.getY() + size.getY();
        List<BoundingBox> neighbours = new ArrayList<>();
        for (PortalStructure other : others) {
            if (other == structure) continue;
            BoundingBox footprint = PortalCarriageBuilder.footprintOf(level, other, dims);
            if (footprint.maxY() < minY || footprint.minY() > maxY) continue;
            if (Math.abs(footprint.getCenter().getX() - room.getX()) > BLOCKED_REACH) continue;
            neighbours.add(footprint);
        }
        neighbours.sort(Comparator.comparingInt(b -> Math.abs(b.getCenter().getX() - room.getX())));
        for (int i = 0; i < Math.min(neighbours.size(), MAX_NEIGHBOURS); i++) {
            boxes.add(toBox(neighbours.get(i)));
        }
        return boxes;
    }

    private static Box toBox(BoundingBox b) {
        return new Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }
}
