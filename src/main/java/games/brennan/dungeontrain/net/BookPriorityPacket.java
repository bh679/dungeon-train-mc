package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.BookIdentity;
import games.brennan.dungeontrain.discord.BookPriorityReporter;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.narrative.BookCopy;
import games.brennan.dungeontrain.narrative.BookModerationState;
import games.brennan.dungeontrain.narrative.BookModerationTag;
import games.brennan.dungeontrain.narrative.BookPriorityTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

/**
 * Client → server: the author chose "Make this a Priority Book" on the vote page of their OWN approved
 * community book, typically one found in their author library (see {@code BookVoteClientEvents}).
 *
 * <p>Validated like {@link BookPrivatePacket}: the held main/off-hand stack is re-resolved via
 * {@link BookIdentity} and a stale or spoofed packet is dropped silently. The stack must read as the
 * author's own released book ({@link BookModerationState#APPROVED}) and not already be a Priority
 * Book. Nothing is stamped here: unlike a withdrawal, a spend can fail (no credits left), so
 * {@link BookPriorityTag} is stamped only when the relay confirms (see
 * {@code PriorityBookResponses}). The relay re-checks authorship, approval and the balance.</p>
 */
public record BookPriorityPacket(String bookType, String bookId) implements CustomPacketPayload {

    private static final int MAX_TYPE_CHARS = 32;
    private static final int MAX_ID_CHARS = 256;

    public static final Type<BookPriorityPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_priority"));

    public static final StreamCodec<FriendlyByteBuf, BookPriorityPacket> STREAM_CODEC =
        StreamCodec.of(BookPriorityPacket::encode, BookPriorityPacket::decode);

    private static void encode(FriendlyByteBuf buf, BookPriorityPacket p) {
        buf.writeUtf(p.bookType == null ? "" : p.bookType, MAX_TYPE_CHARS);
        buf.writeUtf(p.bookId == null ? "" : p.bookId, MAX_ID_CHARS);
    }

    private static BookPriorityPacket decode(FriendlyByteBuf buf) {
        return new BookPriorityPacket(buf.readUtf(MAX_TYPE_CHARS), buf.readUtf(MAX_ID_CHARS));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookPriorityPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!"shared".equals(packet.bookType)) return;
            if (packet.bookId == null || packet.bookId.isEmpty()) return;

            ItemStack stack = matching(player.getMainHandItem(), packet);
            if (stack == null) stack = matching(player.getOffhandItem(), packet);
            if (stack == null) return;
            if (BookModerationTag.read(stack) != BookModerationState.APPROVED) return;
            if (BookPriorityTag.isPriority(stack)) return;

            if (!NetworkConsentMirror.isGranted(player)) return;
            BookPriorityReporter.spend(player.getUUID(), player.getName().getString(), packet.bookId);
        });
    }

    /** {@code held} when its DT identity matches the packet, else null. A crafted copy never matches. */
    private static ItemStack matching(ItemStack held, BookPriorityPacket packet) {
        Optional<BookIdentity> id = BookIdentity.resolve(held);
        if (id.isEmpty() || BookCopy.isCopy(held)) return null;
        return id.get().bookType().equals(packet.bookType) && id.get().bookId().equals(packet.bookId)
            ? held : null;
    }
}
