package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CustomContentPromptClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: this world is starting with custom Train Editor content
 * active and nobody has answered for it yet. Ask whether to continue (in Free
 * Play) or disable the content for this world.
 *
 * <p>Carries a comma-joined list of the package names holding content, so the
 * prompt can name exactly what is active. The client either opens
 * {@link games.brennan.dungeontrain.client.CustomContentPromptScreen} or — if
 * the player has a remembered decision — answers silently; see
 * {@link CustomContentPromptClient}.</p>
 */
public record ShowCustomContentPromptPacket(String packages) implements CustomPacketPayload {

    public static final Type<ShowCustomContentPromptPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "show_custom_content_prompt"));

    public static final StreamCodec<FriendlyByteBuf, ShowCustomContentPromptPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> buf.writeUtf(pkt.packages()),
            buf -> new ShowCustomContentPromptPacket(buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client (mirrors {@code ShowFreePlayConfirmPacket}). */
    public static void handle(ShowCustomContentPromptPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CustomContentPromptClient.onShow(packet.packages()));
    }
}
