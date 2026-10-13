package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.ReadingLanguagesMirror;
import games.brennan.dungeontrain.narrative.ReadingLanguages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the language picked on the book-and-quill sign screen for the book about to be signed.
 * Sent from {@code saveChanges(publish=true)} HEAD, so it lands before vanilla's edit packet (whose
 * handling waits on the text filter) — the same ordering {@link EditorBookAuthorPacket} relies on. The
 * sign-book mixin consumes it via {@link ReadingLanguagesMirror#takeBookLanguage}, which only honours
 * one of the player's reading languages.
 */
public record BookLanguagePacket(String locale) implements CustomPacketPayload {

    public static final Type<BookLanguagePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_language"));

    public static final StreamCodec<FriendlyByteBuf, BookLanguagePacket> STREAM_CODEC =
        StreamCodec.of(BookLanguagePacket::encode, BookLanguagePacket::decode);

    private static void encode(FriendlyByteBuf buf, BookLanguagePacket pkt) {
        buf.writeUtf(pkt.locale(), ReadingLanguages.MAX_LOCALE_LENGTH_WIRE);
    }

    private static BookLanguagePacket decode(FriendlyByteBuf buf) {
        return new BookLanguagePacket(buf.readUtf(ReadingLanguages.MAX_LOCALE_LENGTH_WIRE));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookLanguagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ReadingLanguagesMirror.putBookLanguage(player, packet.locale());
        });
    }
}
