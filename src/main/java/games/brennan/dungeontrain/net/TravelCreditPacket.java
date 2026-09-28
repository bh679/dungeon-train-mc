package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: whether the receiving player's movement is currently being booked as travel on
 * the train — distance in blocks and travelled-carriage progress — and what is withholding it when
 * it is not. Drives the dev-HUD "Travel:" read-out.
 *
 * <p>Server-authoritative for the same reason {@link ActivityStatePacket} is: the carriage AABBs,
 * the interior test and the leader rules all live on the server, and a client cannot tell "gliding
 * over the roof" from "gliding down a corridor" without them.</p>
 *
 * <p>{@code state} is a {@link State} ordinal. Sent from the existing 10-tick boarding scan and
 * only when the value changes, so a player standing still generates no traffic.</p>
 */
public record TravelCreditPacket(int state) implements CustomPacketPayload {

    /** What the server is doing with this player's movement right now. Ordinals are the wire form. */
    public enum State {
        /** Boarded and earning: distance and carriage progress both accrue. */
        CREDITING,
        /** Boarded but gliding outside the train's interior — flight is not travel on the train. */
        ELYTRA_OUTSIDE,
        /** Not on the train at all; nothing to credit. */
        OFF_TRAIN
    }

    public static final Type<TravelCreditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "travel_credit"));

    public static final StreamCodec<FriendlyByteBuf, TravelCreditPacket> STREAM_CODEC =
        StreamCodec.of(TravelCreditPacket::encode, TravelCreditPacket::decode);

    public static TravelCreditPacket of(State state) {
        return new TravelCreditPacket(state.ordinal());
    }

    private static void encode(FriendlyByteBuf buf, TravelCreditPacket p) {
        buf.writeVarInt(Math.max(0, p.state));
    }

    private static TravelCreditPacket decode(FriendlyByteBuf buf) {
        return new TravelCreditPacket(buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TravelCreditPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> VersionHudOverlay.setTravelCredit(packet));
    }
}
