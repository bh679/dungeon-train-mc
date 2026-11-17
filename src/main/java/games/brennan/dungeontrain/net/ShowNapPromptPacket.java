package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.nap.NapPromptClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the player used a natural bed in a run that allows a nap, so ask whether to save
 * and quit ({@code nap/NapService}). {@code night} picks the copy: false is the daytime "You feel
 * comfortable here." prompt shown in place of "You can only sleep at night", true the one shown after
 * drifting to sleep. The answer comes back as {@link NapResponsePacket}.
 */
public record ShowNapPromptPacket(boolean night) implements CustomPacketPayload {

    public static final Type<ShowNapPromptPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "show_nap_prompt"));

    public static final StreamCodec<FriendlyByteBuf, ShowNapPromptPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> buf.writeBoolean(pkt.night()),
            buf -> new ShowNapPromptPacket(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client. */
    public static void handle(ShowNapPromptPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> NapPromptClient.onShow(packet.night()));
    }
}
