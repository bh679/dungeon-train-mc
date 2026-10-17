package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientPortalRoomDepth;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the box of a portal structure and how far its X, Y and Z readouts have to move for the debug
 * screen to report a dimensional carriage as a place of its own.
 *
 * <p><b>What is being hidden.</b> A dimensional carriage is a twin corridor stamped into <i>twin
 * space</i>: the sealed basement under the world's bedrock, or the attic over the upside-down
 * band's lid (see {@code PortalTwinSpace}). Y gives the trick away by about a hundred and fifty
 * blocks, so {@link #yShift} is {@code trainY - } the twin's floor and a player standing in the
 * corridor reads the Y they would have had on the train. X and Z are moved so the centre of the
 * room itself reads {@code 0, 0}: the room is a space apart, not a spot along the track, so
 * {@link #xShift} and {@link #zShift} are minus that centre.</p>
 *
 * <p><b>A place, not a state</b>, the same shape {@link PortalRoomFogPacket} and
 * {@link PortalTrainAudioPacket} use, and for the same reason. "Your coordinates are disguised now"
 * / "they are not any more" has one failure mode that matters: a player who disconnects inside a
 * room never receives the second message and comes back with every reading in the next world shifted
 * by whatever this room's lane happened to be. Describing the box instead means walking out costs no
 * message at all, and a disconnect drops the cached region along with everything else.</p>
 *
 * <p>The box is the structure's (corridors and stamped room copies together, the same
 * {@code structureBox} the fog and the engine audio are gated on) because the disguise has to cover
 * the whole walk, not only the room at the end of it. One shift covers the whole box, so the numbers
 * stay continuous from the corridor into the room and across a twin swap.</p>
 *
 * @param minX   the structure's bounds; outside them this packet does not apply
 * @param xShift blocks to add to a displayed X inside the box
 * @param yShift blocks to add to a displayed Y inside the box
 * @param zShift blocks to add to a displayed Z inside the box. All three {@code 0} means "no
 *               disguise", which is also what a fresh client assumes
 */
public record PortalRoomDepthPacket(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                    int xShift, int yShift, int zShift) implements CustomPacketPayload {

    /** What the server sends to hand the debug screen back its honest numbers. */
    public static PortalRoomDepthPacket none() {
        return new PortalRoomDepthPacket(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * True when this describes a real structure rather than {@link #none()}.
     *
     * <p>Tested on the box rather than on the shifts: a shift of zero is a legitimate answer for
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
                buf.writeVarInt(packet.xShift);
                buf.writeVarInt(packet.yShift);
                buf.writeVarInt(packet.zShift);
            },
            buf -> new PortalRoomDepthPacket(
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalRoomDepthPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPortalRoomDepth.update(packet));
    }
}
