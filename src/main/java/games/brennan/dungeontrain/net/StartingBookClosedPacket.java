package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.event.StartingBookEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

/**
 * Client → server: the player just closed a {@code BookViewScreen} that was
 * showing a stamped starting book (see
 * {@link games.brennan.dungeontrain.narrative.StartingBookTag}).
 *
 * <p>Empty payload — the server already knows who sent it from the
 * {@link DtPayloadContext#player()} reference, and uses that to scan the
 * player's inventory for the stamped book to drop + burn.</p>
 *
 * <p>The actual drop / burn flow lives in
 * {@link StartingBookEvents#handleStartingBookClosed} — keeps the packet
 * dumb (just signal + dispatch) and the burn lifecycle co-located with
 * the rest of the starting-book server logic.</p>
 */
public record StartingBookClosedPacket() implements CustomPacketPayload {

    public static final Type<StartingBookClosedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "starting_book_closed"));

    public static final StreamCodec<FriendlyByteBuf, StartingBookClosedPacket> STREAM_CODEC =
        StreamCodec.unit(new StartingBookClosedPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StartingBookClosedPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            StartingBookEvents.handleStartingBookClosed(player);
        });
    }
}
