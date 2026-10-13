package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.logrequest.LogRequestClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the Developer is asking for this player's logs (request {@code id}). The client
 * shows the consent line, or answers BLOCKED straight away when Developer chat is off — see
 * {@link LogRequestClient}.
 */
public record LogRequestPromptPacket(int id) implements CustomPacketPayload {

    public static final Type<LogRequestPromptPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "log_request_prompt"));

    public static final StreamCodec<FriendlyByteBuf, LogRequestPromptPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeVarInt(p.id()), buf -> new LogRequestPromptPacket(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client. */
    public static void handle(LogRequestPromptPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> LogRequestClient.onPrompt(packet.id()));
    }
}
