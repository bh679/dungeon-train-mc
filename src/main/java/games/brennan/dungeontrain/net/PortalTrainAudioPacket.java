package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.sound.ClientPortalTrainAudio;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the portal structure the player is standing in and the two twin corridors inside it, so the
 * client can carry the train's engine sound through the corridor copy and fade it out in the room.
 *
 * <p><b>Why the client needs telling at all.</b> {@code TrainEngineSound} takes its volume from the
 * player's distance to the nearest loaded Sable sub-level. A twin corridor is not a sub-level — it is
 * ordinary world blocks stamped in the carriage's chunk columns — so the moment somebody transits
 * into one the engine drops to whatever the vertical distance to the real train happens to give.
 * That is a number about the Y lane the structure was assigned, not about anything the player can
 * see or hear a reason for.</p>
 *
 * <p><b>A place, not a state</b> — the same shape {@link PortalRoomFogPacket} uses, and for the same
 * reason. "You are in a corridor now" / "you are not any more" has one failure mode that matters: a
 * player who disconnects inside a corridor never receives the second message and comes back with the
 * engine pinned at full volume. Describing the corridors instead means walking out goes quiet with
 * no message at all, and a disconnect drops the cached region along with everything else.</p>
 *
 * <p><b>The structure box is the gate, not the fade band.</b> The rule has to own the whole structure
 * rather than only the few blocks it fades over: past the fade, deep inside a room, the answer is
 * still "silence", and handing those positions back to the distance curve would let the real train —
 * which is directly overhead, well inside the curve's 64 blocks — become audible again exactly where
 * the point was that it should not be. Outside the box the packet says nothing and the ordinary
 * curve is in charge.</p>
 *
 * <p>Both corridor origins are carried in full rather than the exit being derived from the entry plus
 * an offset. That offset is one corridor plus the pair's room, and the room's length comes from
 * whichever {@code portal_room} template the pair rolled — a figure the client has no way to know,
 * and one this packet would silently get wrong the day the layout changes.</p>
 *
 * @param minX       the structure's bounds, corridors and room copies included; outside them this
 *                   packet does not apply
 * @param entryX     minimum corner of the entry twin corridor, in world blocks
 * @param exitX      minimum corner of the exit twin corridor, in world blocks
 * @param length     the corridors' extent along X, Y and Z — carriage dims, shared by both
 * @param fadeBlocks how far past a corridor mouth the engine takes to reach silence; {@code 0} means
 *                   "no structure", which is also what a fresh client assumes
 */
public record PortalTrainAudioPacket(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                     int entryX, int entryY, int entryZ,
                                     int exitX, int exitY, int exitZ,
                                     int length, int height, int width,
                                     float fadeBlocks) implements CustomPacketPayload {

    /** What the server sends to hand the engine back to its ordinary distance curve. */
    public static PortalTrainAudioPacket none() {
        return new PortalTrainAudioPacket(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0f);
    }

    /** True when this describes a real structure rather than {@link #none()}. */
    public boolean applies() {
        return length > 0 && height > 0 && width > 0 && fadeBlocks > 0.0f;
    }

    public static final Type<PortalTrainAudioPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_train_audio"));

    public static final StreamCodec<FriendlyByteBuf, PortalTrainAudioPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.minX);
                buf.writeVarInt(packet.minY);
                buf.writeVarInt(packet.minZ);
                buf.writeVarInt(packet.maxX);
                buf.writeVarInt(packet.maxY);
                buf.writeVarInt(packet.maxZ);
                buf.writeVarInt(packet.entryX);
                buf.writeVarInt(packet.entryY);
                buf.writeVarInt(packet.entryZ);
                buf.writeVarInt(packet.exitX);
                buf.writeVarInt(packet.exitY);
                buf.writeVarInt(packet.exitZ);
                buf.writeVarInt(packet.length);
                buf.writeVarInt(packet.height);
                buf.writeVarInt(packet.width);
                buf.writeFloat(packet.fadeBlocks);
            },
            buf -> new PortalTrainAudioPacket(
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalTrainAudioPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPortalTrainAudio.update(packet));
    }
}
