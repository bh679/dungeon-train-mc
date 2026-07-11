package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.LetterLecternEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player closed the lectern-letter book edit/sign screen WITHOUT signing.
 * Carries the lectern {@link BlockPos} the screen was opened from so the server can leave the
 * unsigned book &amp; quill resting on that lectern as a "Letter X" draft (see
 * {@link LetterLecternEvents#handleDraftToLectern}).
 *
 * <p>The client only sends this when the close was NOT a sign (the sign path is authoritative
 * server-side via {@code ServerGamePacketListenerImplSignBookMixin} and consumes the book). If the
 * player no longer holds the book &amp; quill (e.g. they actually signed), the server handler is a
 * no-op.</p>
 */
public record LetterDraftToLecternPacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<LetterDraftToLecternPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "letter_draft_to_lectern"));

    public static final StreamCodec<FriendlyByteBuf, LetterDraftToLecternPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeBlockPos(packet.pos),
            buf -> new LetterDraftToLecternPacket(buf.readBlockPos())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LetterDraftToLecternPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            LetterLecternEvents.handleDraftToLectern(player, packet.pos());
        });
    }
}
