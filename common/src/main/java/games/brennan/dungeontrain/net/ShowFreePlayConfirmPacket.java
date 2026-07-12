package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.client.FreePlayConfirmClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

/**
 * Server → client: a tainting action (a creative/spectator switch or a cheat
 * command) was intercepted and held; ask the player to confirm entering Free
 * Play before it commits.
 *
 * <p>Carries a short human-readable label of what triggered it (e.g.
 * {@code "/gamemode creative"}). The client either opens
 * {@link games.brennan.dungeontrain.client.FreePlayConfirmScreen} or, if the
 * player has opted out of the prompt, immediately replies confirmed — see
 * {@link FreePlayConfirmClient}.</p>
 */
public record ShowFreePlayConfirmPacket(String label) implements CustomPacketPayload {

    public static final Type<ShowFreePlayConfirmPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "show_free_play_confirm"));

    public static final StreamCodec<FriendlyByteBuf, ShowFreePlayConfirmPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> buf.writeUtf(pkt.label()),
            buf -> new ShowFreePlayConfirmPacket(buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client (mirrors {@code CinematicIntroPacket}). */
    public static void handle(ShowFreePlayConfirmPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() -> FreePlayConfirmClient.onShow(packet.label()));
    }
}
