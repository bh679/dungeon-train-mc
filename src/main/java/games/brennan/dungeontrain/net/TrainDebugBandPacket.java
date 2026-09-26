package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.TrainDebugState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: which band the player is standing in, for the F3+4 debug panel — as
 * {@link games.brennan.dungeontrain.worldgen.BandLabel} renders it, plus the lap.
 *
 * <p>The band has to come from the server: the cycle geometry it is read from never leaves it. Sent
 * <b>only to players who may open the panel</b>, and only when the label changes — the same
 * gating {@link TrainDebugCarriagePacket} uses, for the same reason: band layout is exactly the
 * kind of spoiler the grant exists to keep off an ordinary client.</p>
 *
 * <p>An empty {@code band} means there is nothing to report (a world without a train).</p>
 */
public record TrainDebugBandPacket(String band, long lap) implements CustomPacketPayload {

    public static final Type<TrainDebugBandPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "train_debug_band"));

    public static final StreamCodec<FriendlyByteBuf, TrainDebugBandPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            TrainDebugBandPacket::decode
        );

    public TrainDebugBandPacket {
        band = band == null ? "" : band;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(band);
        buf.writeVarLong(lap);
    }

    public static TrainDebugBandPacket decode(FriendlyByteBuf buf) {
        return new TrainDebugBandPacket(buf.readUtf(), buf.readVarLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TrainDebugBandPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> TrainDebugState.setBand(packet.band, packet.lap));
    }
}
