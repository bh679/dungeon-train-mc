package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.BookAuthorChatMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: whether this player wants the "The book by X burns" author line in chat, sent on
 * login and again whenever they toggle it while connected.
 *
 * <p>The preference is a CLIENT-scope config ({@code ClientDisplayConfig.BOOK_AUTHOR_BURN_CHAT}) but
 * the moment it fires — a burnable book igniting — is decided server-side in
 * {@code StartingBookEvents.onEntityJoinLevel}. So the client has to tell the server, exactly as
 * {@link PoliticalFilterSyncPacket} does for the political filter.</p>
 */
public record BookAuthorChatSyncPacket(boolean enabled) implements CustomPacketPayload {

    public static final Type<BookAuthorChatSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_author_chat_sync"));

    public static final StreamCodec<FriendlyByteBuf, BookAuthorChatSyncPacket> STREAM_CODEC =
        StreamCodec.of(BookAuthorChatSyncPacket::encode, BookAuthorChatSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, BookAuthorChatSyncPacket pkt) {
        buf.writeBoolean(pkt.enabled());
    }

    private static BookAuthorChatSyncPacket decode(FriendlyByteBuf buf) {
        return new BookAuthorChatSyncPacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookAuthorChatSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            BookAuthorChatMirror.set(player, packet.enabled());
        });
    }
}
