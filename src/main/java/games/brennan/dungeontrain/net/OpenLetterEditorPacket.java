package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.client.LetterEditorClient;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the player right-clicked a lectern with a book &amp; quill and the letters
 * feature is active for them, so open the vanilla book edit/sign screen for that book. The server
 * has already suppressed vanilla placement (the book stays in hand) and recorded the pending
 * lectern; signing routes to the letter flow, closing-without-signing leaves the draft on the
 * lectern (see {@code LetterLecternEvents}).
 *
 * <p>Carries the interaction {@code hand} (0 = main, 1 = off) so the sign packet targets the right
 * inventory slot, the lectern {@code pos} (tracked client-side for the close-without-sign packet),
 * and the current {@code pages} — the pages are sent explicitly because the client's block-interaction
 * prediction (book removed from hand → onto the lectern) has not rolled back yet when this packet is
 * processed, so reading the held stack could momentarily see an empty hand. The real book stays in the
 * server-side hand, so vanilla signing still finds it.</p>
 */
public record OpenLetterEditorPacket(int hand, BlockPos pos, List<String> pages) implements CustomPacketPayload {

    public static final Type<OpenLetterEditorPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "open_letter_editor"));

    public static final StreamCodec<FriendlyByteBuf, OpenLetterEditorPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> {
                buf.writeVarInt(pkt.hand());
                buf.writeBlockPos(pkt.pos());
                buf.writeCollection(pkt.pages(), FriendlyByteBuf::writeUtf);
            },
            buf -> new OpenLetterEditorPacket(
                buf.readVarInt(),
                buf.readBlockPos(),
                buf.readCollection(ArrayList::new, FriendlyByteBuf::readUtf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client (mirrors {@code ShowFreePlayConfirmPacket}). */
    public static void handle(OpenLetterEditorPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() -> LetterEditorClient.open(packet.hand(), packet.pos(), packet.pages()));
    }
}
