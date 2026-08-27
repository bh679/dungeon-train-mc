package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientPortalCrossing;
import games.brennan.dungeontrain.portal.PortalCrossingLight;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: how far along a portal corridor you are, as the ramp {@link PortalCrossingLight} defines, so
 * the client can hold its lightmap at a constant across the crossing and the swap has nothing to
 * pop.
 *
 * <p><b>A value, not a place</b> — which is the opposite of {@link PortalRoomSkyPacket} and
 * {@link PortalRoomFogPacket}, and worth saying why. Those send a box because a box outlives a
 * dropped message: the client tests the camera against it and needs no "you have left" packet. A
 * portal corridor cannot be sent that way. Its blocks ride a Sable sub-level at far shipyard
 * coordinates while the player and the swap arithmetic are in rendered track space, so the box the
 * client would have to test is the <i>rendered</i> one — which moves with the train every tick, and
 * re-sending it every tick is exactly the cost the box form exists to avoid. The server already
 * computes this player's corridor-local X each tick to decide the swap, so it sends the one number
 * that falls out of it and the client expires it on a timer
 * ({@link ClientPortalCrossing}).</p>
 *
 * <p><b>One byte.</b> The value only ever reaches the screen through an eased lift, so resolution
 * past 1/255 is not visible, and the packet is deduped server-side against the last one sent — a
 * player standing still in a corridor sends nothing at all.</p>
 *
 * @param wire {@code 0}..{@code 255}. {@code 0} is "no corridor" and is also what a fresh client
 *             assumes
 */
public record PortalCrossingPacket(int wire) implements CustomPacketPayload {

    /** What the server sends to take the hold away. */
    public static PortalCrossingPacket none() {
        return new PortalCrossingPacket(0);
    }

    public static final Type<PortalCrossingPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_crossing"));

    public static final StreamCodec<FriendlyByteBuf, PortalCrossingPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeByte(packet.wire),
            buf -> new PortalCrossingPacket(buf.readUnsignedByte()));

    /** The ramp this names, clamped — see {@link PortalCrossingLight#fromWire}. */
    public float intensity() {
        return PortalCrossingLight.fromWire(wire);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalCrossingPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPortalCrossing.update(packet.intensity()));
    }
}
