package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientPortalRoomFarCopies;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C: the shape of the endless room the player is in, so a client running Distant Horizons can
 * carry its copies on past the ones the server has stamped, out to DH's horizon.
 *
 * <p><b>A place, not a state</b>, like {@link PortalRoomFogPacket}: the client decides frame by frame
 * whether the camera is inside {@code region*}, so a disconnect inside a room cannot leave far
 * copies drawn in the next world. Cleared with {@link #none()}.</p>
 *
 * <p>The client builds the look of a copy from the room's own blocks, which it already has loaded —
 * nothing here describes a block. What it cannot see for itself is the grid (where the room is and
 * how big a tile is), which tiles really stand ({@code stamped}, so a far copy fills every gap the
 * fog used to hide and never doubles a real one), and where it must not draw ({@code blocked}: the
 * pair's own corridors and every other pair's structure in reach).</p>
 *
 * @param mode    {@link #MODE_NONE}, {@link #MODE_OPEN} or {@link #MODE_REPETITION}
 * @param roomX   minimum corner of the base tile (tile {@code 0,0}); tiles step by the room size
 * @param sizeX   the room's size, which is also the tile pitch on X and Z
 * @param regionMinX world bounds (inclusive) the camera must be inside for this packet to apply
 * @param stamped every tile a real copy stands in, base included
 * @param blocked world boxes no far copy may overlap
 */
public record PortalRoomFarCopiesPacket(int mode,
                                        int roomX, int roomY, int roomZ,
                                        int sizeX, int sizeY, int sizeZ,
                                        int regionMinX, int regionMinY, int regionMinZ,
                                        int regionMaxX, int regionMaxY, int regionMaxZ,
                                        List<TileRef> stamped, List<Box> blocked)
    implements CustomPacketPayload {

    public static final int MODE_NONE = 0;
    public static final int MODE_OPEN = 1;
    public static final int MODE_REPETITION = 2;

    /** Hard cap on either list, so a malformed or hostile packet cannot ask for a huge allocation. */
    public static final int MAX_ENTRIES = 1024;

    /** A tile index on the room's grid. */
    public record TileRef(int x, int z) {}

    /** An inclusive world box. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean intersects(int x0, int y0, int z0, int x1, int y1, int z1) {
            return x0 <= maxX && x1 >= minX && y0 <= maxY && y1 >= minY && z0 <= maxZ && z1 >= minZ;
        }
    }

    public PortalRoomFarCopiesPacket {
        stamped = List.copyOf(stamped);
        blocked = List.copyOf(blocked);
    }

    /** What the server sends when the player is in no endless room. */
    public static PortalRoomFarCopiesPacket none() {
        return new PortalRoomFarCopiesPacket(MODE_NONE, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, List.of(), List.of());
    }

    public boolean isNone() {
        return mode == MODE_NONE || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0;
    }

    /** True when {@code (x, y, z)} is inside the region this packet applies to. */
    public boolean contains(double x, double y, double z) {
        if (isNone()) return false;
        return x >= regionMinX && x < regionMaxX + 1
            && y >= regionMinY && y < regionMaxY + 1
            && z >= regionMinZ && z < regionMaxZ + 1;
    }

    public static final Type<PortalRoomFarCopiesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_room_far_copies"));

    public static final StreamCodec<FriendlyByteBuf, PortalRoomFarCopiesPacket> STREAM_CODEC =
        StreamCodec.of(PortalRoomFarCopiesPacket::write, PortalRoomFarCopiesPacket::read);

    private static void write(FriendlyByteBuf buf, PortalRoomFarCopiesPacket p) {
        buf.writeVarInt(p.mode);
        buf.writeVarInt(p.roomX);
        buf.writeVarInt(p.roomY);
        buf.writeVarInt(p.roomZ);
        buf.writeVarInt(p.sizeX);
        buf.writeVarInt(p.sizeY);
        buf.writeVarInt(p.sizeZ);
        buf.writeVarInt(p.regionMinX);
        buf.writeVarInt(p.regionMinY);
        buf.writeVarInt(p.regionMinZ);
        buf.writeVarInt(p.regionMaxX);
        buf.writeVarInt(p.regionMaxY);
        buf.writeVarInt(p.regionMaxZ);
        buf.writeVarInt(Math.min(p.stamped.size(), MAX_ENTRIES));
        for (int i = 0; i < Math.min(p.stamped.size(), MAX_ENTRIES); i++) {
            TileRef t = p.stamped.get(i);
            buf.writeVarInt(t.x());
            buf.writeVarInt(t.z());
        }
        buf.writeVarInt(Math.min(p.blocked.size(), MAX_ENTRIES));
        for (int i = 0; i < Math.min(p.blocked.size(), MAX_ENTRIES); i++) {
            Box b = p.blocked.get(i);
            buf.writeVarInt(b.minX());
            buf.writeVarInt(b.minY());
            buf.writeVarInt(b.minZ());
            buf.writeVarInt(b.maxX());
            buf.writeVarInt(b.maxY());
            buf.writeVarInt(b.maxZ());
        }
    }

    private static PortalRoomFarCopiesPacket read(FriendlyByteBuf buf) {
        int mode = buf.readVarInt();
        int roomX = buf.readVarInt();
        int roomY = buf.readVarInt();
        int roomZ = buf.readVarInt();
        int sizeX = buf.readVarInt();
        int sizeY = buf.readVarInt();
        int sizeZ = buf.readVarInt();
        int rMinX = buf.readVarInt();
        int rMinY = buf.readVarInt();
        int rMinZ = buf.readVarInt();
        int rMaxX = buf.readVarInt();
        int rMaxY = buf.readVarInt();
        int rMaxZ = buf.readVarInt();
        int stampedCount = boundedCount(buf.readVarInt());
        List<TileRef> stamped = new ArrayList<>(stampedCount);
        for (int i = 0; i < stampedCount; i++) {
            stamped.add(new TileRef(buf.readVarInt(), buf.readVarInt()));
        }
        int blockedCount = boundedCount(buf.readVarInt());
        List<Box> blocked = new ArrayList<>(blockedCount);
        for (int i = 0; i < blockedCount; i++) {
            blocked.add(new Box(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new PortalRoomFarCopiesPacket(mode, roomX, roomY, roomZ, sizeX, sizeY, sizeZ,
            rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ, stamped, blocked);
    }

    private static int boundedCount(int count) {
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("portal_room_far_copies: list of " + count + " entries");
        }
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalRoomFarCopiesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPortalRoomFarCopies.update(packet));
    }
}
