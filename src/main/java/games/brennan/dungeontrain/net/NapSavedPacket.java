package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.nap.NapQuit;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the nap is on disk ({@code nap/NapService}), so leave to the title screen and
 * delete {@code levelId}, the save folder the nap came from.
 */
public record NapSavedPacket(String levelId) implements CustomPacketPayload {

    public static final Type<NapSavedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "nap_saved"));

    public static final StreamCodec<FriendlyByteBuf, NapSavedPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> buf.writeUtf(pkt.levelId()),
            buf -> new NapSavedPacket(buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client. */
    public static void handle(NapSavedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> NapQuit.leave(packet.levelId()));
    }
}
