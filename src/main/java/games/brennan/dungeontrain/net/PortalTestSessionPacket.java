package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PortalTestSessionState;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: whether this player is currently standing in a test dimensional carriage, so the worldspace
 * menu can offer the way back out of it.
 *
 * <p>A state rather than a place, unlike {@link PortalRoomSkyPacket} — and safe as one because the
 * server owns both edges: it sends {@code true} as it teleports the player in and {@code false} as
 * it brings them back, and the session it mirrors does not survive a disconnect either. A client
 * that missed the {@code false} rejoins to a fresh {@code false}.</p>
 */
public record PortalTestSessionPacket(boolean active, String roomName, boolean reseed)
    implements CustomPacketPayload {

    /**
     * What the server sends when the trip is over.
     *
     * @param reseed the world's reseed-on-test switch, which rides along on every send so the
     *               editor's toggle is never showing a state the server does not hold
     */
    public static PortalTestSessionPacket none(boolean reseed) {
        return new PortalTestSessionPacket(false, "", reseed);
    }

    /** This player's session as it stands — none if they are not on a test trip. */
    public static PortalTestSessionPacket of(ServerPlayer player) {
        boolean reseed = DungeonTrainWorldData.get(player.getServer().overworld()).isPortalTestReseed();
        PortalTestSession.Session trip = PortalTestSession.get(player.getUUID());
        return trip == null ? none(reseed) : new PortalTestSessionPacket(true, trip.roomName(), reseed);
    }

    public static final Type<PortalTestSessionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_test_session"));

    public static final StreamCodec<FriendlyByteBuf, PortalTestSessionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.active);
                buf.writeUtf(packet.roomName);
                buf.writeBoolean(packet.reseed);
            },
            buf -> new PortalTestSessionPacket(buf.readBoolean(), buf.readUtf(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalTestSessionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PortalTestSessionState.update(packet));
    }
}
