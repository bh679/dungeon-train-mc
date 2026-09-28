package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.EditorBookAuthorPending;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the custom author name typed on the book sign screen while standing in an
 * editor plot, sent immediately BEFORE vanilla's {@code ServerboundEditBookPacket} so it is
 * waiting in {@link EditorBookAuthorPending} when {@code signBook} runs.
 *
 * <p>Ordering: this payload is enqueued onto the server thread on arrival, while vanilla's
 * {@code handleEditBook} first round-trips the text filter
 * ({@code filterTextPacket(...).thenAcceptAsync}) — so ours lands first. If it somehow didn't,
 * the book simply keeps the player's real name; nothing breaks.</p>
 *
 * <p>The name is untrusted and capped on the wire at a generous bound; the sign intercept sanitizes
 * and clamps it to {@link EditorBookAuthorPending#MAX_AUTHOR_LENGTH} before it reaches an item.
 * The server independently requires the signer to be inside an editor plot — a packet sent from
 * the live train is consumed and ignored.</p>
 */
public record EditorBookAuthorPacket(String author) implements CustomPacketPayload {

    /** Wire cap — well above {@link EditorBookAuthorPending#MAX_AUTHOR_LENGTH}, just bounds the buffer. */
    private static final int WIRE_MAX = 256;

    public static final Type<EditorBookAuthorPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_book_author"));

    public static final StreamCodec<FriendlyByteBuf, EditorBookAuthorPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeUtf(packet.author(), WIRE_MAX),
            buf -> new EditorBookAuthorPacket(buf.readUtf(WIRE_MAX))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorBookAuthorPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            EditorBookAuthorPending.put(player.getUUID(), packet.author());
        });
    }
}
