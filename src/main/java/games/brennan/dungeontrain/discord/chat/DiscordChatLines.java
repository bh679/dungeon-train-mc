package games.brennan.dungeontrain.discord.chat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DiscordChatLinePacket;
import games.brennan.dungeontrain.net.DiscordLineBindPacket;
import games.brennan.dungeontrain.net.DiscordReactionsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Server side of Discord reactions in chat. DiscordPresence tells us (through
 * {@link games.brennan.dungeontrain.compat.DiscordInboundBridge}) when a player's chat line was posted to
 * Discord and when a message's reactions change; this keeps {@link DiscordLineRegistry} and sends the
 * clients what they need to draw the reactions on the right line.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DiscordChatLines {

    private DiscordChatLines() {}

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DiscordLineRegistry.clear();
    }

    /** {@code player}'s line {@code rawText} became Discord message {@code messageId}. Any thread. */
    public static void onGameChatPosted(UUID player, String rawText, String messageId) {
        DiscordLineRegistry.put(messageId, DiscordLineRegistry.Kind.EVERYONE, player);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        String raw = rawText.length() > 256 ? rawText.substring(0, 256) : rawText;
        server.execute(() -> PacketDistributor.sendToAllPlayers(new DiscordLineBindPacket(messageId, player, raw)));
    }

    /**
     * Print {@code line} — Discord message {@code messageId} — in {@code player}'s chat so its reactions can
     * follow. Server thread. Falls back to a plain system message when there is no id.
     */
    public static void deliver(ServerPlayer player, @Nullable String messageId, Component line) {
        if (messageId == null || messageId.isBlank()) {
            player.sendSystemMessage(line);
            return;
        }
        DiscordLineRegistry.put(messageId, DiscordLineRegistry.Kind.OWNER, player.getUUID());
        DungeonTrainNet.sendTo(player, new DiscordChatLinePacket(messageId, line));
    }

    /** A message's reactions changed. Gateway thread: hops to the server thread. */
    public static void onReactions(String messageId, List<ChatReaction> list, @Nullable ChatReactionEvent event) {
        DiscordLineRegistry.Entry entry = DiscordLineRegistry.get(messageId);
        if (entry == null) return; // not a line anyone here was shown
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        DiscordReactionsPacket packet = new DiscordReactionsPacket(messageId, list, event);
        server.execute(() -> {
            if (entry.kind() == DiscordLineRegistry.Kind.EVERYONE) {
                PacketDistributor.sendToAllPlayers(packet);
                return;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner());
            if (owner != null) DungeonTrainNet.sendTo(owner, packet);
        });
    }
}
