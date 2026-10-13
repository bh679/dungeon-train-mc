package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.logrequest.LogRequestManager;
import games.brennan.dungeontrain.logrequest.LogRequestManager.Answer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player's answer to Developer log request {@code id} — send, decline, or
 * blocked (Developer chat is off on this client). See {@link LogRequestManager#onAnswer}.
 */
public record LogRequestAnswerPacket(int id, Answer answer) implements CustomPacketPayload {

    public static final Type<LogRequestAnswerPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "log_request_answer"));

    public static final StreamCodec<FriendlyByteBuf, LogRequestAnswerPacket> STREAM_CODEC =
        StreamCodec.of(LogRequestAnswerPacket::encode, LogRequestAnswerPacket::decode);

    private static void encode(FriendlyByteBuf buf, LogRequestAnswerPacket p) {
        buf.writeVarInt(p.id());
        buf.writeEnum(p.answer());
    }

    private static LogRequestAnswerPacket decode(FriendlyByteBuf buf) {
        return new LogRequestAnswerPacket(buf.readVarInt(), buf.readEnum(Answer.class));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LogRequestAnswerPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            LogRequestManager.onAnswer(player, packet.id(), packet.answer());
        });
    }
}
