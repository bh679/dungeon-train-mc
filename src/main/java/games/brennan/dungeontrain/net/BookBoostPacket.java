package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.BookIdentity;
import games.brennan.dungeontrain.discord.BookBoostReporter;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.narrative.BookCopy;
import games.brennan.dungeontrain.narrative.BookModerationTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

/**
 * Client → server: the reader chose "Boost" on the vote page of SOMEBODY ELSE's community book,
 * spending one of their Priority Books to make it turn up more often in other players' trains.
 *
 * <p>The reader-side sibling of {@link BookPriorityPacket}, validated the same way: the held main/off-hand
 * stack is re-resolved via {@link BookIdentity} and a stale or spoofed packet is dropped silently. The
 * stack must NOT read as the reader's own book (that is the author route). A reader may boost the same
 * book again, so there is no "already done" gate. Nothing is stamped here, since a boost can fail (no
 * credits left, the book is full); {@code BookBoostTag} counts it when the relay confirms. The relay
 * re-checks authorship, approval and balance.</p>
 */
public record BookBoostPacket(String bookType, String bookId) implements CustomPacketPayload {

    private static final int MAX_TYPE_CHARS = 32;
    private static final int MAX_ID_CHARS = 256;

    public static final Type<BookBoostPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_boost"));

    public static final StreamCodec<FriendlyByteBuf, BookBoostPacket> STREAM_CODEC =
        StreamCodec.of(BookBoostPacket::encode, BookBoostPacket::decode);

    private static void encode(FriendlyByteBuf buf, BookBoostPacket p) {
        buf.writeUtf(p.bookType == null ? "" : p.bookType, MAX_TYPE_CHARS);
        buf.writeUtf(p.bookId == null ? "" : p.bookId, MAX_ID_CHARS);
    }

    private static BookBoostPacket decode(FriendlyByteBuf buf) {
        return new BookBoostPacket(buf.readUtf(MAX_TYPE_CHARS), buf.readUtf(MAX_ID_CHARS));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookBoostPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!"shared".equals(packet.bookType)) return;
            if (packet.bookId == null || packet.bookId.isEmpty()) return;

            ItemStack stack = matching(player.getMainHandItem(), packet);
            if (stack == null) stack = matching(player.getOffhandItem(), packet);
            if (stack == null) return;
            if (BookModerationTag.read(stack).isOwn()) return;

            if (!NetworkConsentMirror.isGranted(player)) return;
            BookBoostReporter.boost(player.getUUID(), player.getName().getString(), packet.bookId);
        });
    }

    /** {@code held} when its DT identity matches the packet, else null. A crafted copy never matches. */
    private static ItemStack matching(ItemStack held, BookBoostPacket packet) {
        Optional<BookIdentity> id = BookIdentity.resolve(held);
        if (id.isEmpty() || BookCopy.isCopy(held)) return null;
        return id.get().bookType().equals(packet.bookType) && id.get().bookId().equals(packet.bookId)
            ? held : null;
    }
}
