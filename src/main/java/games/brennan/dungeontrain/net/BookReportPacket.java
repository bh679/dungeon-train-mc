package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.BookIdentity;
import games.brennan.dungeontrain.discord.BookReportReporter;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.narrative.BookReportTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

/**
 * Client → server: the player asked for the community book they are reading to be PULLED from the
 * shared pool — the ⚠ Report control on the train's vote page (see {@code BookVoteClientEvents}).
 *
 * <p>Sibling to {@link BookVotePacket} and validated identically: the server never trusts the
 * identity blind, re-resolving the held main/off-hand stack via {@link BookIdentity} and dropping a
 * stale or spoofed report (book thrown / swapped mid-read) silently. On a match it ALWAYS stamps
 * {@link BookReportTag} on the stack — so the control reads as reported and goes inert on reopen
 * even offline — and only then, gated on the player's network consent
 * ({@link NetworkConsentMirror#isGranted}, the same fail-closed rule every reporter uses), hands
 * off to {@link BookReportReporter} for the relay POST.</p>
 *
 * <p>Player-written {@code shared} books only, enforced here and not just client-side, so a
 * modified client cannot report dev-authored content. Unlike a vote there is no value and no
 * changing your mind: a report is one-way, and the relay dedupes it per (book, player).</p>
 */
public record BookReportPacket(String bookType, String bookId) implements CustomPacketPayload {

    /** Defensive wire caps — same bounds as {@link BookVotePacket}. */
    private static final int MAX_TYPE_CHARS = 32;
    private static final int MAX_ID_CHARS = 256;

    public static final Type<BookReportPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_report"));

    public static final StreamCodec<FriendlyByteBuf, BookReportPacket> STREAM_CODEC =
        StreamCodec.of(BookReportPacket::encode, BookReportPacket::decode);

    private static void encode(FriendlyByteBuf buf, BookReportPacket p) {
        buf.writeUtf(p.bookType == null ? "" : p.bookType, MAX_TYPE_CHARS);
        buf.writeUtf(p.bookId == null ? "" : p.bookId, MAX_ID_CHARS);
    }

    private static BookReportPacket decode(FriendlyByteBuf buf) {
        String bookType = buf.readUtf(MAX_TYPE_CHARS);
        String bookId = buf.readUtf(MAX_ID_CHARS);
        return new BookReportPacket(bookType, bookId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookReportPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // Only PLAYER-WRITTEN community books are reportable — mirror of the client gate.
            if (!"shared".equals(packet.bookType)) return;
            if (packet.bookId == null || packet.bookId.isEmpty()) return;

            ItemStack stack = matching(player.getMainHandItem(), packet);
            if (stack == null) stack = matching(player.getOffhandItem(), packet);
            if (stack == null) return; // held book no longer matches (thrown/swapped) → register nothing

            // Always stamp — the reported state is local and survives without the relay. The stack
            // lives in the player's inventory, so vanilla sync carries the tag back to the client.
            BookReportTag.stamp(stack);

            // Per-player, fail-closed: the report only leaves the machine with the same network
            // consent that gates every other reporter. No consent → local stamp only, no relay POST.
            if (!NetworkConsentMirror.isGranted(player)) return;
            BookReportReporter.report(player.getUUID(), player.getName().getString(), packet);
        });
    }

    /** {@code held} when its resolved DT identity matches the packet's {@code (bookType, bookId)}, else null. */
    private static ItemStack matching(ItemStack held, BookReportPacket packet) {
        Optional<BookIdentity> id = BookIdentity.resolve(held);
        if (id.isEmpty()) return null;
        return id.get().bookType().equals(packet.bookType) && id.get().bookId().equals(packet.bookId)
            ? held : null;
    }
}
