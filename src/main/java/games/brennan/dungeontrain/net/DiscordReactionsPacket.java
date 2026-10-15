package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.chat.ChatReactionTracker;
import games.brennan.dungeontrain.discord.chat.ChatReaction;
import games.brennan.dungeontrain.discord.chat.ChatReactionCodec;
import games.brennan.dungeontrain.discord.chat.ChatReactionEvent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Server → clients that saw the line: Discord message {@code messageId} now carries {@code reactions}
 * (a full snapshot, so applying it twice is harmless); {@code event} says what changed, when known.
 */
public record DiscordReactionsPacket(String messageId, List<ChatReaction> reactions, @Nullable ChatReactionEvent event)
        implements CustomPacketPayload {

    public static final Type<DiscordReactionsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "discord_reactions"));

    public static final StreamCodec<FriendlyByteBuf, DiscordReactionsPacket> STREAM_CODEC =
        StreamCodec.of(DiscordReactionsPacket::encode, DiscordReactionsPacket::decode);

    private static void encode(FriendlyByteBuf buf, DiscordReactionsPacket pkt) {
        buf.writeUtf(pkt.messageId(), 32);
        ChatReactionCodec.write(buf, pkt.reactions(), pkt.event());
    }

    private static DiscordReactionsPacket decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(32);
        List<ChatReaction> list = ChatReactionCodec.readList(buf);
        return new DiscordReactionsPacket(id, list, ChatReactionCodec.readEvent(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DiscordReactionsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatReactionTracker.applyDiscord(packet.messageId(), packet.reactions(), packet.event()));
    }
}
