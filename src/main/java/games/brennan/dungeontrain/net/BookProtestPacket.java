package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.BookIdentity;
import games.brennan.dungeontrain.discord.BookProtestReporter;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.narrative.BookProtestTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

/**
 * Client → server: the author objected to the train's verdict on their OWN community book — the
 * Protest control on the vote page (see {@code BookVoteClientEvents}), which stands where Report
 * stands on somebody else's book.
 *
 * <p>Validated like {@link BookReportPacket}: the server never trusts the identity blind, re-resolving
 * the held main/off-hand stack via {@link BookIdentity} and dropping a stale or spoofed packet (book
 * thrown / swapped mid-read) silently. On a match it ALWAYS stamps {@link BookProtestTag} — so the
 * control reads as spent on reopen even offline — and only then, gated on network consent
 * ({@link NetworkConsentMirror#isGranted}), hands off to {@link BookProtestReporter}.</p>
 *
 * <p>Player-written {@code shared} books only, and — unlike a report — the RELAY additionally checks
 * that the caller is the book's author before recording anything. This handler cannot make that check
 * (the client does not know the book's author uuid), which is exactly why the relay does: a protest is
 * a claim about your own writing and nobody else's.</p>
 *
 * <p>One-way, like a report, and it changes nothing about the book. See {@code bookprotests.js}.</p>
 */
public record BookProtestPacket(String bookType, String bookId) implements CustomPacketPayload {

    /** Defensive wire caps — same bounds as {@link BookReportPacket}. */
    private static final int MAX_TYPE_CHARS = 32;
    private static final int MAX_ID_CHARS = 256;

    public static final Type<BookProtestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_protest"));

    public static final StreamCodec<FriendlyByteBuf, BookProtestPacket> STREAM_CODEC =
        StreamCodec.of(BookProtestPacket::encode, BookProtestPacket::decode);

    private static void encode(FriendlyByteBuf buf, BookProtestPacket p) {
        buf.writeUtf(p.bookType == null ? "" : p.bookType, MAX_TYPE_CHARS);
        buf.writeUtf(p.bookId == null ? "" : p.bookId, MAX_ID_CHARS);
    }

    private static BookProtestPacket decode(FriendlyByteBuf buf) {
        String bookType = buf.readUtf(MAX_TYPE_CHARS);
        String bookId = buf.readUtf(MAX_ID_CHARS);
        return new BookProtestPacket(bookType, bookId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookProtestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!"shared".equals(packet.bookType)) return;
            if (packet.bookId == null || packet.bookId.isEmpty()) return;

            ItemStack stack = matching(player.getMainHandItem(), packet);
            if (stack == null) stack = matching(player.getOffhandItem(), packet);
            if (stack == null) return; // held book no longer matches (thrown/swapped) → register nothing

            // Always stamp — the spent state is local and survives without the relay, so the control
            // stops inviting the same protest every time the book is opened.
            BookProtestTag.stamp(stack);

            if (!NetworkConsentMirror.isGranted(player)) return;
            BookProtestReporter.protest(player.getUUID(), player.getName().getString(), packet);
        });
    }

    /** {@code held} when its resolved DT identity matches the packet's {@code (bookType, bookId)}, else null. */
    private static ItemStack matching(ItemStack held, BookProtestPacket packet) {
        Optional<BookIdentity> id = BookIdentity.resolve(held);
        if (id.isEmpty()) return null;
        return id.get().bookType().equals(packet.bookType) && id.get().bookId().equals(packet.bookId)
            ? held : null;
    }
}
