package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.wind.ClientWind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: everything the client needs to compute the train wind itself — the profile JSON, the
 * world's wind seed and the world X where the wind starts ({@link Long#MIN_VALUE} for none). Sent on login and
 * on {@code /dungeontrain debug wind reload|on|off}; never per tick, because {@code WindField} is
 * deterministic and both sides evaluate it from the shared game time.
 */
public record WindProfilePacket(String profileJson, int seed, long startX, boolean on) implements CustomPacketPayload {

    /** Generous cap; the shipped profile is under 2 KB. */
    private static final int MAX_JSON = 32 * 1024;

    public static final Type<WindProfilePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "wind_profile"));

    public static final StreamCodec<FriendlyByteBuf, WindProfilePacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> p.encode(buf), WindProfilePacket::decode);

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(profileJson, MAX_JSON);
        buf.writeInt(seed);
        buf.writeLong(startX);
        buf.writeBoolean(on);
    }

    public static WindProfilePacket decode(FriendlyByteBuf buf) {
        return new WindProfilePacket(buf.readUtf(MAX_JSON), buf.readInt(), buf.readLong(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WindProfilePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientWind.apply(packet));
    }
}
