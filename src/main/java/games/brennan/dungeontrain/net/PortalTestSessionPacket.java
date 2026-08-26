package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PortalTestSessionState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
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
public record PortalTestSessionPacket(boolean active, String roomName) implements CustomPacketPayload {

    /** What the server sends when the trip is over. */
    public static PortalTestSessionPacket none() {
        return new PortalTestSessionPacket(false, "");
    }

    public static final Type<PortalTestSessionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_test_session"));

    public static final StreamCodec<FriendlyByteBuf, PortalTestSessionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.active);
                buf.writeUtf(packet.roomName);
            },
            buf -> new PortalTestSessionPacket(buf.readBoolean(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalTestSessionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PortalTestSessionState.update(packet));
    }
}
