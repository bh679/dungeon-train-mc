package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientBookSuspension;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: how long this player's book uploads are paused for, so the signing screen can
 * refuse before they spend a book (see {@code BookEditScreenSuspensionMixin}).
 *
 * <p>The pause itself is the relay's ({@code booksuspensions.js}), mirrored server-side in
 * {@link games.brennan.dungeontrain.narrative.BookUploadSuspensions}; this carries it the last hop to
 * the screen that has to grey a button out. Sent when the relay's verdict lands, when a sign is
 * refused, and at login while a window is still open — so a reconnect never hands back a live button.</p>
 *
 * <p>{@code remainingSec} is a DURATION, never a deadline: client and server clocks are unrelated, and
 * the same reasoning applies one hop further out between the game and the relay. {@code strikes} is
 * carried for the message, not for any decision. {@code remainingSec <= 0} clears the pause, which is
 * how a lifted window is announced.</p>
 */
public record BookSuspensionSyncPacket(long remainingSec, int strikes) implements CustomPacketPayload {

    /** A day is far past the 1h ceiling the relay can hand out — a defensive wire bound, nothing more. */
    private static final long MAX_REMAINING_SEC = 86_400L;

    public static final Type<BookSuspensionSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_suspension_sync"));

    public static final StreamCodec<FriendlyByteBuf, BookSuspensionSyncPacket> STREAM_CODEC =
        StreamCodec.of(BookSuspensionSyncPacket::encode, BookSuspensionSyncPacket::decode);

    /** Clamp to a sane window; a negative or absurd value degrades to "not suspended". */
    public static BookSuspensionSyncPacket of(long remainingSec, int strikes) {
        long clamped = remainingSec <= 0L ? 0L : Math.min(remainingSec, MAX_REMAINING_SEC);
        return new BookSuspensionSyncPacket(clamped, Math.max(0, strikes));
    }

    /** The packet that lifts the pause — a player who has served it, or was never suspended. */
    public static BookSuspensionSyncPacket cleared() {
        return new BookSuspensionSyncPacket(0L, 0);
    }

    private static void encode(FriendlyByteBuf buf, BookSuspensionSyncPacket p) {
        buf.writeVarLong(Math.max(0L, p.remainingSec));
        buf.writeVarInt(Math.max(0, p.strikes));
    }

    private static BookSuspensionSyncPacket decode(FriendlyByteBuf buf) {
        return of(buf.readVarLong(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookSuspensionSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientBookSuspension.set(packet.remainingSec, packet.strikes));
    }
}
