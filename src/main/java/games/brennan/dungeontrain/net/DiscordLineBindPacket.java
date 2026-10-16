package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.chat.ChatReactionTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server → every client: the chat line {@code sender} just typed ({@code rawText}) was posted to
 * Discord as message {@code messageId}. The client ties that id to the line it printed, so Discord
 * reactions on the message can later show on it ({@link ChatReactionTracker#bind}).
 */
public record DiscordLineBindPacket(String messageId, UUID sender, String rawText) implements CustomPacketPayload {

    public static final Type<DiscordLineBindPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "discord_line_bind"));

    public static final StreamCodec<FriendlyByteBuf, DiscordLineBindPacket> STREAM_CODEC =
        StreamCodec.of(DiscordLineBindPacket::encode, DiscordLineBindPacket::decode);

    private static void encode(FriendlyByteBuf buf, DiscordLineBindPacket pkt) {
        buf.writeUtf(pkt.messageId(), 32);
        buf.writeUUID(pkt.sender());
        buf.writeUtf(pkt.rawText(), 256);
    }

    private static DiscordLineBindPacket decode(FriendlyByteBuf buf) {
        return new DiscordLineBindPacket(buf.readUtf(32), buf.readUUID(), buf.readUtf(256));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DiscordLineBindPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatReactionTracker.bind(packet.messageId(), packet.sender(), packet.rawText()));
    }
}
