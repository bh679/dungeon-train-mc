package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.live.LiveLife;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the id of the life you are on ({@code player/LifeKey}).
 *
 * <p>A life is a server fact, but streaming and watching the Live Feed are reported to the relay by
 * the client. The client passes this id along with both, so the relay can keep the one-life "Longest
 * Stream" and "Longest Viewer" boards. Sent at login and at every respawn.</p>
 */
public record LiveLifePacket(String lifeId) implements CustomPacketPayload {

    private static final int MAX_ID = 64;

    public static final Type<LiveLifePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_life"));

    public static final StreamCodec<FriendlyByteBuf, LiveLifePacket> STREAM_CODEC =
        StreamCodec.of(LiveLifePacket::encode, LiveLifePacket::decode);

    private static void encode(FriendlyByteBuf buf, LiveLifePacket p) {
        buf.writeUtf(p.lifeId == null ? "" : p.lifeId, MAX_ID);
    }

    private static LiveLifePacket decode(FriendlyByteBuf buf) {
        return new LiveLifePacket(buf.readUtf(MAX_ID));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveLifePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> LiveLife.set(packet.lifeId()));
    }
}
