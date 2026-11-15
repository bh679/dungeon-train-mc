package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.nap.NapService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player answered the nap prompt ({@link ShowNapPromptPacket}).
 * {@code accepted=true} is Main Menu: save the nap and end the session. False is Stay awake /
 * Wake up.; a sleeping player is woken.
 */
public record NapResponsePacket(boolean accepted) implements CustomPacketPayload {

    public static final Type<NapResponsePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "nap_response"));

    public static final StreamCodec<FriendlyByteBuf, NapResponsePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> buf.writeBoolean(pkt.accepted()),
            buf -> new NapResponsePacket(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(NapResponsePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            NapService.onResponse(player, packet.accepted());
        });
    }
}
