package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientPortalRoomDepth;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the box of a portal structure and how far its Y readout has to move for the debug screen to
 * report the depth the player believes they are at.
 *
 * <p><b>What is being hidden.</b> A dimensional carriage is a twin corridor stamped into <i>twin
 * space</i> — the sealed basement under the world's bedrock, or the attic over the upside-down
 * band's lid (see {@code PortalTwinSpace}). It is stamped in the carriage's own chunk columns, so X
 * and Z already read exactly what the equivalent carriage would give; Y is the one coordinate that
 * gives the trick away, and it does so by about a hundred and fifty blocks. {@link #yShift} is
 * {@code trainY - } the twin's floor, so a player standing in the corridor reads the Y they would
 * have had on the train.</p>
 *
 * <p><b>A place, not a state</b> — the same shape {@link PortalRoomFogPacket} and
 * {@link PortalTrainAudioPacket} use, and for the same reason. "Your coordinates are disguised now"
 * / "they are not any more" has one failure mode that matters: a player who disconnects inside a
 * room never receives the second message and comes back with every reading in the next world shifted
 * by whatever this room's lane happened to be. Describing the box instead means walking out costs no
 * message at all, and a disconnect drops the cached region along with everything else.</p>
 *
 * <p>The box is the structure's — corridors and stamped room copies together, the same
 * {@code structureBox} the fog and the engine audio are gated on — because the disguise has to cover
 * the whole walk, not only the room at the end of it.</p>
 *
 * @param minX   the structure's bounds; outside them this packet does not apply
 * @param yShift blocks to add to a displayed Y inside the box. {@code 0} means "no disguise", which
 *               is also what a fresh client assumes
 */
public record PortalRoomDepthPacket(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                    int yShift) implements CustomPacketPayload {

    /** What the server sends to hand the debug screen back its honest numbers. */
    public static PortalRoomDepthPacket none() {
        return new PortalRoomDepthPacket(0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * True when this describes a real structure rather than {@link #none()}.
     *
     * <p>Tested on the box rather than on {@link #yShift}: a shift of zero is a legitimate answer for
     * a twin that happens to sit level with the train, and treating it as "no structure" would be
     * harmless today but wrong the moment anything else rides this packet.</p>
     */
    public boolean applies() {
        return maxY > minY;
    }

    /** Whether {@code (x, y, z)} is inside the structure this packet describes. */
    public boolean contains(double x, double y, double z) {
        return applies()
            && x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    public static final Type<PortalRoomDepthPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_room_depth"));

    public static final StreamCodec<FriendlyByteBuf, PortalRoomDepthPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.minX);
                buf.writeVarInt(packet.minY);
                buf.writeVarInt(packet.minZ);
                buf.writeVarInt(packet.maxX);
                buf.writeVarInt(packet.maxY);
                buf.writeVarInt(packet.maxZ);
                buf.writeVarInt(packet.yShift);
            },
            buf -> new PortalRoomDepthPacket(
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalRoomDepthPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPortalRoomDepth.update(packet));
    }
}
