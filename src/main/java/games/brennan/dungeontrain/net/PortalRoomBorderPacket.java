package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientPortalRoomBorder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the room box of a World Border dimensional carriage the player is in or around, so the
 * client can draw a world border on its four sides — see {@code PortalRoomBorderRenderer}.
 *
 * <p><b>A region, not a flag</b>, for the reason {@link PortalRoomFogPacket} gives: a disconnect
 * never strands a client drawing a border, because the cache goes with the connection and the
 * renderer only ever draws what it was last told. Red-versus-blue is decided on the client from the
 * camera each frame for the same reason, never streamed.</p>
 *
 * <p>The bounds are the room's own box, not the padded fog region: the border stands on the room's
 * walls, and the clearance around it is where a player walks out to. The vertical extent drawn is
 * not here either — it is the twin seal's, which the client already knows.</p>
 *
 * @param minX the room's west face, in blocks (inclusive edge)
 * @param maxX the room's east face — one past its last column, so {@code maxX - minX} is its length
 * @param minY the room's floor row, used only for the twin-space fallback when seal culling is off
 * @param maxY one past the room's top row
 */
public record PortalRoomBorderPacket(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    implements CustomPacketPayload {

    /** What the server sends to take the border away. */
    public static PortalRoomBorderPacket none() {
        return new PortalRoomBorderPacket(0, 0, 0, 0, 0, 0);
    }

    /** True for a real region; {@link #none} and anything degenerate is not one. */
    public boolean present() {
        return maxX > minX && maxZ > minZ && maxY > minY;
    }

    public static final Type<PortalRoomBorderPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_room_border"));

    public static final StreamCodec<FriendlyByteBuf, PortalRoomBorderPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.minX);
                buf.writeVarInt(packet.minY);
                buf.writeVarInt(packet.minZ);
                buf.writeVarInt(packet.maxX);
                buf.writeVarInt(packet.maxY);
                buf.writeVarInt(packet.maxZ);
            },
            buf -> new PortalRoomBorderPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalRoomBorderPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPortalRoomBorder.update(packet));
    }
}
