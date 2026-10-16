package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.chat.ChatReactionTracker;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → one client: a chat line that came from Discord message {@code messageId} — a reply in the
 * player's thread, or a Developer message — printed by the client so it can later carry that message's
 * reactions. Replaces {@code sendSystemMessage} for lines that have a Discord id.
 */
public record DiscordChatLinePacket(String messageId, Component line) implements CustomPacketPayload {

    public static final Type<DiscordChatLinePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "discord_chat_line"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DiscordChatLinePacket> STREAM_CODEC =
        StreamCodec.of(DiscordChatLinePacket::encode, DiscordChatLinePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, DiscordChatLinePacket pkt) {
        buf.writeUtf(pkt.messageId(), 32);
        ComponentSerialization.STREAM_CODEC.encode(buf, pkt.line());
    }

    private static DiscordChatLinePacket decode(RegistryFriendlyByteBuf buf) {
        return new DiscordChatLinePacket(buf.readUtf(32), ComponentSerialization.STREAM_CODEC.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DiscordChatLinePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatReactionTracker.printDiscordLine(packet.messageId(), packet.line()));
    }
}
