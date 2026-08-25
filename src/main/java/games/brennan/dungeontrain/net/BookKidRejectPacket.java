package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.BookIdentity;
import games.brennan.dungeontrain.discord.BookKidRejectReporter;
import games.brennan.dungeontrain.event.KidTesterMirror;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.narrative.BookKidRejectTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

/**
 * Client → server: a kid-safe tester asked for the community book they are reading to be kept away
 * from CHILDREN — the red "Remove for kids" control beside the ⚠ report on the train's vote page
 * (see {@code BookVoteClientEvents}).
 *
 * <p>Sibling to {@link BookReportPacket} and validated identically: the server never trusts the
 * identity blind, re-resolving the held main/off-hand stack via {@link BookIdentity} and dropping a
 * stale or spoofed request (book thrown / swapped mid-read) silently.</p>
 *
 * <p><b>It is a narrower verdict than a report, and stays narrow.</b> A report asks for the book to
 * be pulled from everyone; this only moves its kid rating, leaving the adult pool alone. The two
 * controls are independent — casting one never casts the other — because "not for a child" and "not
 * for anyone" are different claims and a reader who makes the first has not made the second.</p>
 *
 * <p><b>Only a marked tester may send this.</b> The client draws the control because the relay told
 * it to ({@link KidTesterSyncPacket}), but a client is not evidence: this handler re-asks
 * {@link KidTesterMirror}, which is fail-closed, and the relay re-asks its own roster again before
 * writing. A modified client gains nothing at either hop.</p>
 */
public record BookKidRejectPacket(String bookType, String bookId) implements CustomPacketPayload {

    /** Defensive wire caps — same bounds as {@link BookReportPacket}. */
    private static final int MAX_TYPE_CHARS = 32;
    private static final int MAX_ID_CHARS = 256;

    public static final Type<BookKidRejectPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_kid_reject"));

    public static final StreamCodec<FriendlyByteBuf, BookKidRejectPacket> STREAM_CODEC =
        StreamCodec.of(BookKidRejectPacket::encode, BookKidRejectPacket::decode);

    private static void encode(FriendlyByteBuf buf, BookKidRejectPacket p) {
        buf.writeUtf(p.bookType == null ? "" : p.bookType, MAX_TYPE_CHARS);
        buf.writeUtf(p.bookId == null ? "" : p.bookId, MAX_ID_CHARS);
    }

    private static BookKidRejectPacket decode(FriendlyByteBuf buf) {
        String bookType = buf.readUtf(MAX_TYPE_CHARS);
        String bookId = buf.readUtf(MAX_ID_CHARS);
        return new BookKidRejectPacket(bookType, bookId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookKidRejectPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // Only PLAYER-WRITTEN community books — mirror of the client gate, and of the relay's.
            if (!"shared".equals(packet.bookType)) return;
            if (packet.bookId == null || packet.bookId.isEmpty()) return;

            // The mark is what permits this, and the client's belief about it is not consulted.
            // Checked BEFORE the stamp: a player who is not a tester must not end up with a book
            // that reads as kid-rejected when nothing was rejected.
            if (!KidTesterMirror.isTester(player)) return;

            ItemStack stack = matching(player.getMainHandItem(), packet);
            if (stack == null) stack = matching(player.getOffhandItem(), packet);
            if (stack == null) return; // held book no longer matches (thrown/swapped) → register nothing

            // Always stamp — the spent state is local and survives without the relay. The stack lives
            // in the player's inventory, so vanilla sync carries the tag back to the client.
            BookKidRejectTag.stamp(stack);

            // Per-player, fail-closed: the verdict only leaves the machine with the same network
            // consent that gates every other reporter. No consent → local stamp only, no relay POST.
            if (!NetworkConsentMirror.isGranted(player)) return;
            BookKidRejectReporter.report(player.getUUID(), player.getName().getString(), packet);
        });
    }

    /** {@code held} when its resolved DT identity matches the packet's {@code (bookType, bookId)}, else null. */
    private static ItemStack matching(ItemStack held, BookKidRejectPacket packet) {
        Optional<BookIdentity> id = BookIdentity.resolve(held);
        if (id.isEmpty()) return null;
        return id.get().bookType().equals(packet.bookType) && id.get().bookId().equals(packet.bookId)
            ? held : null;
    }
}
