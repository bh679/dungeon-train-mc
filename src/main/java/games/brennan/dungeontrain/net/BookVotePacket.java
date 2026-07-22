package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.BookIdentity;
import games.brennan.dungeontrain.discord.BookVoteReporter;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.narrative.BookVoteTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

/**
 * Client → server: the player cast (or changed) a 👍/👎 vote on the DT book they are reading —
 * from the vote page's buttons or the Y/N hotkeys (see {@code BookVoteClientEvents}).
 *
 * <p>The server never trusts the identity blind: it re-resolves the held main/off-hand stack via
 * {@link BookIdentity} and only acts when it matches the packet, so a stale or spoofed vote (book
 * thrown / swapped mid-read) is dropped silently. On a match it ALWAYS stamps
 * {@link BookVoteTag} on the stack — the vote works fully offline (burn flame color, reopen
 * seeding, inventory sync back to the client) — and only then, gated on the player's network
 * consent ({@link NetworkConsentMirror#isGranted}, the same fail-closed rule every reporter uses),
 * hands off to {@link BookVoteReporter} for the relay POST.</p>
 *
 * <p>{@code variantIndex} is which text variant the player was shown (random/starting books;
 * {@code -1} when not applicable) — report-only context, never part of the identity match.</p>
 */
public record BookVotePacket(String bookType, String bookId, int vote, int variantIndex)
        implements CustomPacketPayload {

    /** Defensive wire caps (bookIds are basenames / story#letter keys / pool-id strings). */
    private static final int MAX_TYPE_CHARS = 32;
    private static final int MAX_ID_CHARS = 256;

    public static final Type<BookVotePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_vote"));

    public static final StreamCodec<FriendlyByteBuf, BookVotePacket> STREAM_CODEC =
        StreamCodec.of(BookVotePacket::encode, BookVotePacket::decode);

    private static void encode(FriendlyByteBuf buf, BookVotePacket p) {
        buf.writeUtf(p.bookType == null ? "" : p.bookType, MAX_TYPE_CHARS);
        buf.writeUtf(p.bookId == null ? "" : p.bookId, MAX_ID_CHARS);
        buf.writeByte(p.vote);
        buf.writeVarInt(p.variantIndex);
    }

    private static BookVotePacket decode(FriendlyByteBuf buf) {
        String bookType = buf.readUtf(MAX_TYPE_CHARS);
        String bookId = buf.readUtf(MAX_ID_CHARS);
        int vote = buf.readByte();
        int variantIndex = buf.readVarInt();
        return new BookVotePacket(bookType, bookId, vote, variantIndex);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookVotePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (packet.vote != 1 && packet.vote != -1) return;
            // Only PLAYER-WRITTEN community books are votable — mirror of the client gate, enforced
            // server-side so a modified client can't vote on dev-authored content.
            if (!"shared".equals(packet.bookType)) return;
            if (packet.bookId == null || packet.bookId.isEmpty()) return;

            ItemStack stack = matching(player.getMainHandItem(), packet);
            if (stack == null) stack = matching(player.getOffhandItem(), packet);
            if (stack == null) return; // held book no longer matches (thrown/swapped) → register nothing

            // Always stamp — offline votes still drive the burn color + reopen seeding. The stack
            // lives in the player's inventory, so vanilla sync carries the tag back to the client.
            BookVoteTag.stamp(stack, packet.vote);

            // Per-player, fail-closed: the vote only leaves the machine with the same network consent
            // that gates every other reporter. No consent → local stamp only, no relay POST.
            if (!NetworkConsentMirror.isGranted(player)) return;
            BookVoteReporter.report(player.getUUID(), player.getName().getString(), packet);
        });
    }

    /** {@code held} when its resolved DT identity matches the packet's {@code (bookType, bookId)}, else null. */
    private static ItemStack matching(ItemStack held, BookVotePacket packet) {
        Optional<BookIdentity> id = BookIdentity.resolve(held);
        if (id.isEmpty()) return null;
        return id.get().bookType().equals(packet.bookType) && id.get().bookId().equals(packet.bookId)
            ? held : null;
    }
}
