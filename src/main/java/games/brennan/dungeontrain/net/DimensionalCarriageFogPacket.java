package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientDimensionalCarriageFog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the region of an endless dimensional carriage the player is in, so the client can fog it.
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
 * <h2>The falloff is a shape, not a value</h2>
 * <p>A Bedrockless room fogs harder the further out of it you walk, and the server could send the
 * current strength every tick as the player moves. It sends the <i>shape</i> instead — where the
 * ramp starts, how long it runs, where it ends — and the client evaluates it at the camera each
 * frame. Same reason the region is a place rather than a flag: a strength streamed per tick is a
 * state that can be missed, arrive late, or be left stale by a disconnect, and it would step in
 * tick-sized jumps rather than with the player's stride.</p>
 *
 * <p>The room's own box is not sent either — it is these bounds shrunk by {@code falloff}
 * horizontally, which is exactly how the server grew them. One number describes the swept clearance,
 * the padded region and the ramp, so no two of them can drift.</p>
 *
 * @param minX      world bounds of the stamped copies, inclusive — grown by the clearance for a
 *                  Bedrockless room, which owns swept space rather than copies
 * @param radius    the mode's nominal fog distance in blocks, seen at the room's own walls — five
 *                  rooms out for the tiling modes, the clearance for Bedrockless; {@code 0} means
 *                  "no longer in a room", which is also what a fresh client assumes
 * @param falloff   blocks of ramp between the room's walls and the edge of the region. {@code 0} is
 *                  a flat fog at {@code radius} everywhere inside the bounds, which is what every
 *                  mode but Bedrockless sends and what all of them sent before the ramp existed
 * @param minRadius the fog distance at the far edge of the ramp. Ignored when {@code falloff} is
 *                  {@code 0}
 */
public record DimensionalCarriageFogPacket(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                  float radius, int falloff, float minRadius)
    implements CustomPacketPayload {

    /** What the server sends to take the fog away. */
    public static DimensionalCarriageFogPacket none() {
        return new DimensionalCarriageFogPacket(0, 0, 0, 0, 0, 0, 0.0f, 0, 0.0f);
    }

    public static final Type<DimensionalCarriageFogPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dimensional_carriage_fog"));

    public static final StreamCodec<FriendlyByteBuf, DimensionalCarriageFogPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.minX);
                buf.writeVarInt(packet.minY);
                buf.writeVarInt(packet.minZ);
                buf.writeVarInt(packet.maxX);
                buf.writeVarInt(packet.maxY);
                buf.writeVarInt(packet.maxZ);
                buf.writeFloat(packet.radius);
                buf.writeVarInt(packet.falloff);
                buf.writeFloat(packet.minRadius);
            },
            buf -> new DimensionalCarriageFogPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(),
                buf.readVarInt(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DimensionalCarriageFogPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientDimensionalCarriageFog.update(packet));
    }
}
