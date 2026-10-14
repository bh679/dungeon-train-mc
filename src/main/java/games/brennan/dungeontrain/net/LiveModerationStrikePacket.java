package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.LiveModeration;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server: I masked a listed word in my own text.
 *
 * <p>The client censors as the player types and again as a chat line leaves, so the server only ever
 * sees {@code ****} — it would never know a strike was earned. This packet says so, and carries what
 * was typed and which terms matched: the relay keeps that as the evidence a ban appeal is judged
 * against. The server records the strike against the sender, rate-limited, and only while the filter
 * is active for them. A modified client could simply not send it, but such a client is already Free
 * Play and the server-side pass still censors what it sends; nothing worse than silence is possible.</p>
 */
public record LiveModerationStrikePacket(Source source, String text, List<String> terms) implements CustomPacketPayload {

    /** Where the word was typed. Ordinal-encoded; append only. */
    public enum Source {
        /** A chat line, caught as it was sent. */
        CHAT,
        /** The chat box, caught as the word was typed. */
        CHAT_FIELD,
        /** A book page or title, caught as the word was typed. */
        BOOK_FIELD;

        public String relayName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Longest line of evidence carried; the relay keeps the same. */
    public static final int MAX_TEXT = 500;
    public static final int MAX_TERMS = 20;
    public static final int MAX_TERM = 64;

    public static final Type<LiveModerationStrikePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_moderation_strike"));

    public static final StreamCodec<FriendlyByteBuf, LiveModerationStrikePacket> STREAM_CODEC =
        StreamCodec.of(LiveModerationStrikePacket::encode, LiveModerationStrikePacket::decode);

    public LiveModerationStrikePacket {
        text = text == null ? "" : text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        List<String> clean = new ArrayList<>();
        if (terms != null) for (String t : terms) {
            if (clean.size() >= MAX_TERMS) break;
            if (t != null && !t.isEmpty()) clean.add(t.length() > MAX_TERM ? t.substring(0, MAX_TERM) : t);
        }
        terms = List.copyOf(clean);
    }

    private static void encode(FriendlyByteBuf buf, LiveModerationStrikePacket p) {
        buf.writeVarInt(p.source.ordinal());
        buf.writeUtf(p.text, MAX_TEXT);
        buf.writeVarInt(p.terms.size());
        for (String t : p.terms) buf.writeUtf(t, MAX_TERM);
    }

    private static LiveModerationStrikePacket decode(FriendlyByteBuf buf) {
        int ord = buf.readVarInt();
        Source[] all = Source.values();
        Source source = ord >= 0 && ord < all.length ? all[ord] : Source.CHAT;
        String text = buf.readUtf(MAX_TEXT);
        int n = Math.min(MAX_TERMS, Math.max(0, buf.readVarInt()));
        List<String> terms = new ArrayList<>(n);
        for (int i = 0; i < n; i++) terms.add(buf.readUtf(MAX_TERM));
        return new LiveModerationStrikePacket(source, text, terms);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveModerationStrikePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sender) {
                LiveModeration.strikeFromClient(sender, packet.source(), packet.text(), packet.terms());
            }
        });
    }
}
