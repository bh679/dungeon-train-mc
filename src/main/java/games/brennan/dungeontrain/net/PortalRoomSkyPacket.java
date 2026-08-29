package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientPortalRoomSky;
import games.brennan.dungeontrain.portal.PortalRoomSky;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the box of a portal room that asked to be lit as though it stood outdoors, and which sky it
 * stands under, so the client can lift its lightmap while the camera is inside it.
 *
 * <p><b>A place, not a state</b> — the same design {@link PortalRoomFogPacket} spells out, for the
 * same reason. "You are daylit now" and "you are not any more" is the obvious pair of messages, and
 * a player who disconnects inside a room never receives the second one and comes back permanently
 * bright. The server describes the room instead and the client decides, frame by frame, whether the
 * camera is in it; walking out needs no message at all.</p>
 *
 * <p>Separate from the fog packet rather than a field on it, because the two do not cover the same
 * ground. Fog is sent only for modes that fog and reaches into the clearance a Bedrockless room
 * swept; daylight is sent for any mode whose template opted in and stops at the room's own walls —
 * the corridors back to the train are not meant to be daylit, and the void outside a bedrockless
 * room certainly is not.</p>
 *
 * <p>The bounds are of what has actually been <b>stamped</b>, for the reason the fog packet's are:
 * a copy can fail to appear, and lighting a room the player can walk out of the side of would give
 * that away.</p>
 *
 * @param minX world bounds of the stamped copies, inclusive
 * @param sky    {@link PortalRoomSky#ordinal()} of the sky the room stands under. {@code 0} is
 *               {@link PortalRoomSky#NONE} — "no longer in a daylit room", which is also what a
 *               fresh client assumes
 * @param editor true when this box is a room standing on its editor plot rather than one in the
 *               world. The lift itself is identical; the flag exists so the author can switch the
 *               plot's lighting off while they build without that switch reaching a live room or a
 *               test session — a preference about the editor, not about the room
 */
public record PortalRoomSkyPacket(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                  int sky, boolean editor)
    implements CustomPacketPayload {

    /** What the server sends to take the daylight away. */
    public static PortalRoomSkyPacket none() {
        return new PortalRoomSkyPacket(0, 0, 0, 0, 0, 0, PortalRoomSky.NONE.ordinal(), false);
    }

    /** A room in the world — a live pair or a test session. The lift is not optional for these. */
    public static PortalRoomSkyPacket inWorld(int minX, int minY, int minZ,
                                              int maxX, int maxY, int maxZ, int sky) {
        return new PortalRoomSkyPacket(minX, minY, minZ, maxX, maxY, maxZ, sky, false);
    }

    /**
     * A room standing on its editor plot. Carries the same box and sky as the live form and differs
     * only in that the author can switch it off — see {@code ClientDisplayConfig.isEditorPlotLighting}.
     */
    public static PortalRoomSkyPacket onPlot(int minX, int minY, int minZ,
                                             int maxX, int maxY, int maxZ, int sky) {
        return new PortalRoomSkyPacket(minX, minY, minZ, maxX, maxY, maxZ, sky, true);
    }

    public static final Type<PortalRoomSkyPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_room_sky"));

    public static final StreamCodec<FriendlyByteBuf, PortalRoomSkyPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.minX);
                buf.writeVarInt(packet.minY);
                buf.writeVarInt(packet.minZ);
                buf.writeVarInt(packet.maxX);
                buf.writeVarInt(packet.maxY);
                buf.writeVarInt(packet.maxZ);
                buf.writeVarInt(packet.sky);
                buf.writeBoolean(packet.editor);
            },
            buf -> new PortalRoomSkyPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean()));

    /**
     * The sky this names, or {@link PortalRoomSky#NONE} when it names nothing this build knows.
     *
     * <p>A client on a different build than the server will eventually be sent an ordinal its own
     * enum does not have, and the right answer to that is an ordinary unlit room rather than an
     * exception on the packet thread — see {@link PortalRoomSky#byOrdinal}.</p>
     */
    public PortalRoomSky skyKind() {
        return PortalRoomSky.byOrdinal(sky);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalRoomSkyPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPortalRoomSky.update(packet));
    }
}
