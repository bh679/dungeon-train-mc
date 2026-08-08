package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientPortalRoomFog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the region of an endless portal room the player is in, so the client can fog it.
 *
 * <p><b>A region, not a flag.</b> The obvious design — tell the client "you are fogged now" and
 * "you are not fogged now" — has one failure mode that matters: a player who disconnects while
 * inside a room never receives the second message, and comes back permanently fogged. So the server
 * describes the <i>place</i> instead, and the client decides frame by frame whether the camera is in
 * it. Walking out clears the fog with no message at all, and a disconnect drops the cached region
 * along with everything else. The same shape {@code ClientNetherBand} uses for the transition
 * bands.</p>
 *
 * <p>The bounds are of what has actually been <b>stamped</b>, not of what the mode asked for. Copies
 * of the room can fail to appear — an unloaded chunk, a spent budget, another pair's structure in
 * the way — and clamping the fog to the built extent is what makes those cases invisible rather than
 * a hole in the world.</p>
 *
 * <p>Sent when the region changes and cleared with a zero {@code radius}. The client eases toward
 * whatever it was last told, so a packet arriving a tick late costs nothing.</p>
 *
 * @param minX   world bounds of the stamped copies, inclusive — grown by the clearance for a
 *               Bedrockless room, which owns swept space rather than copies
 * @param radius the mode's nominal fog distance in blocks — five rooms out for the tiling modes, the
 *               clearance for Bedrockless; {@code 0} means "no longer in a room", which is also what
 *               a fresh client assumes
 */
public record PortalRoomFogPacket(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                  float radius) implements CustomPacketPayload {

    /** What the server sends to take the fog away. */
    public static PortalRoomFogPacket none() {
        return new PortalRoomFogPacket(0, 0, 0, 0, 0, 0, 0.0f);
    }

    public static final Type<PortalRoomFogPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_room_fog"));

    public static final StreamCodec<FriendlyByteBuf, PortalRoomFogPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.minX);
                buf.writeVarInt(packet.minY);
                buf.writeVarInt(packet.minZ);
                buf.writeVarInt(packet.maxX);
                buf.writeVarInt(packet.maxY);
                buf.writeVarInt(packet.maxZ);
                buf.writeFloat(packet.radius);
            },
            buf -> new PortalRoomFogPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalRoomFogPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPortalRoomFog.update(packet));
    }
}
