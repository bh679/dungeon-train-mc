package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.live.LiveMoments;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.MomentKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the Live Eye streamer just earned an advancement, took a photo or signed a book.
 *
 * <p>The server sees the moment; the streamer's own client holds the stream token that proves to the
 * relay it is on air, and the Stream settings that say which moments to share — so the server only
 * tells, and {@link LiveMoments} decides and posts. Only the live streamer is ever sent one.</p>
 *
 * <p>{@code title} is the advancement or book title (empty for a photo), {@code excerpt} a book's first
 * lines (empty otherwise), {@code png} a photo's picture (empty otherwise), at most {@link #MAX_PNG} bytes.</p>
 */
public record LiveMomentPacket(MomentKind kind, String title, String excerpt, byte[] png) implements CustomPacketPayload {

    /** Biggest picture sent; the relay refuses bigger. A Discord-sized photo is a fraction of this. */
    public static final int MAX_PNG = 600 * 1024;
    private static final int MAX_TITLE = 256;
    private static final int MAX_EXCERPT = 1024;

    public static final Type<LiveMomentPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_moment"));

    public static final StreamCodec<FriendlyByteBuf, LiveMomentPacket> STREAM_CODEC =
        StreamCodec.of(LiveMomentPacket::encode, LiveMomentPacket::decode);

    public LiveMomentPacket {
        title = title == null ? "" : cap(title, MAX_TITLE);
        excerpt = excerpt == null ? "" : cap(excerpt, MAX_EXCERPT);
        png = png == null || png.length > MAX_PNG ? new byte[0] : png;
    }

    private static String cap(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static void encode(FriendlyByteBuf buf, LiveMomentPacket p) {
        buf.writeEnum(p.kind);
        buf.writeUtf(p.title, MAX_TITLE);
        buf.writeUtf(p.excerpt, MAX_EXCERPT);
        buf.writeByteArray(p.png);
    }

    private static LiveMomentPacket decode(FriendlyByteBuf buf) {
        MomentKind kind = buf.readEnum(MomentKind.class);
        String title = buf.readUtf(MAX_TITLE);
        String excerpt = buf.readUtf(MAX_EXCERPT);
        return new LiveMomentPacket(kind, title, excerpt, buf.readByteArray(MAX_PNG));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveMomentPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> LiveMoments.onServerSaid(packet));
    }
}
