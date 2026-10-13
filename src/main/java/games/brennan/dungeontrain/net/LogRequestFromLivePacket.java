package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.logrequest.LogRequestManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: this client saw a Developer log request in the livestream chat aimed at it (the
 * relay only issues those for verified Developer accounts). The server runs the usual request flow,
 * rate-limited per player — see {@link LogRequestManager#onLiveRequest}.
 */
public record LogRequestFromLivePacket() implements CustomPacketPayload {

    public static final Type<LogRequestFromLivePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "log_request_from_live"));

    public static final StreamCodec<FriendlyByteBuf, LogRequestFromLivePacket> STREAM_CODEC =
        StreamCodec.unit(new LogRequestFromLivePacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LogRequestFromLivePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            LogRequestManager.onLiveRequest(player);
        });
    }
}
