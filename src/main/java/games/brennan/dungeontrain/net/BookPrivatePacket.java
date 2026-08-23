package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.BookIdentity;
import games.brennan.dungeontrain.discord.BookPrivateReporter;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.narrative.BookPrivateTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

/**
 * Client → server: the author withdrew their OWN community book from circulation, or put it back —
 * the Make Private / Make Public control on the vote page (see {@code BookVoteClientEvents}), which
 * stands where Report stands on somebody else's book.
 *
 * <p>Validated like {@link BookReportPacket}: the server never trusts the identity blind, re-resolving
 * the held main/off-hand stack via {@link BookIdentity} and dropping a stale or spoofed packet (book
 * thrown / swapped mid-read) silently. On a match it ALWAYS stamps {@link BookPrivateTag} — so the
 * control reads correctly on reopen even offline — and only then, gated on network consent
 * ({@link NetworkConsentMirror#isGranted}), hands off to {@link BookPrivateReporter}.</p>
 *
 * <p>Player-written {@code shared} books only, and — like a protest — the RELAY checks that the caller
 * is the book's author before acting. Unlike a report or a vote this carries a VALUE and is
 * reversible: withdrawing a book and putting it back are the same packet with a different flag, so an
 * accidental tap is undoable.</p>
 *
 * <p>Withdrawing is not a moderation action. The book keeps whatever verdict it had; its writer has
 * simply decided they would rather strangers did not read it. See {@code books.setPrivate}.</p>
 */
public record BookPrivatePacket(String bookType, String bookId, boolean makePrivate) implements CustomPacketPayload {

    /** Defensive wire caps — same bounds as {@link BookReportPacket}. */
    private static final int MAX_TYPE_CHARS = 32;
    private static final int MAX_ID_CHARS = 256;

    public static final Type<BookPrivatePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_private"));

    public static final StreamCodec<FriendlyByteBuf, BookPrivatePacket> STREAM_CODEC =
        StreamCodec.of(BookPrivatePacket::encode, BookPrivatePacket::decode);

    private static void encode(FriendlyByteBuf buf, BookPrivatePacket p) {
        buf.writeUtf(p.bookType == null ? "" : p.bookType, MAX_TYPE_CHARS);
        buf.writeUtf(p.bookId == null ? "" : p.bookId, MAX_ID_CHARS);
        buf.writeBoolean(p.makePrivate);
    }

    private static BookPrivatePacket decode(FriendlyByteBuf buf) {
        String bookType = buf.readUtf(MAX_TYPE_CHARS);
        String bookId = buf.readUtf(MAX_ID_CHARS);
        boolean makePrivate = buf.readBoolean();
        return new BookPrivatePacket(bookType, bookId, makePrivate);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookPrivatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!"shared".equals(packet.bookType)) return;
            if (packet.bookId == null || packet.bookId.isEmpty()) return;

            ItemStack stack = matching(player.getMainHandItem(), packet);
            if (stack == null) stack = matching(player.getOffhandItem(), packet);
            if (stack == null) return; // held book no longer matches (thrown/swapped) → register nothing

            BookPrivateTag.stamp(stack, packet.makePrivate);

            if (!NetworkConsentMirror.isGranted(player)) return;
            BookPrivateReporter.setPrivate(player.getUUID(), player.getName().getString(), packet);
        });
    }

    /** {@code held} when its resolved DT identity matches the packet's {@code (bookType, bookId)}, else null. */
    private static ItemStack matching(ItemStack held, BookPrivatePacket packet) {
        Optional<BookIdentity> id = BookIdentity.resolve(held);
        if (id.isEmpty()) return null;
        return id.get().bookType().equals(packet.bookType) && id.get().bookId().equals(packet.bookId)
            ? held : null;
    }
}
