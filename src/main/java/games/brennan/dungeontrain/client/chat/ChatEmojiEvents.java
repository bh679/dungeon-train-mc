package games.brennan.dungeontrain.client.chat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.chat.emoji.EmojiText;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Emoji in the in-game chat, both ways.
 *
 * <p>Incoming: every chat line is passed through {@link EmojiText} at LOWEST priority — after any
 * other listener has had its say — so Discord's emoji, custom emoji and mentions draw properly; player
 * lines are also remembered so their Discord reactions can find them ({@link ChatReactionTracker}).
 * Outgoing: {@code :shortcode:} in a chat line (not a command) becomes the emoji at HIGH priority, before
 * the Live chat bridge and the Discord relay read it, so Discord shows the emoji too.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ChatEmojiEvents {

    private ChatEmojiEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onChatReceived(ClientChatReceivedEvent event) {
        if (event instanceof ClientChatReceivedEvent.Player p) {
            String raw = p.getPlayerChatMessage().signedContent();
            event.setMessage(ChatReactionTracker.onPlayerChat(p.getSender(), raw, event.getMessage()));
        } else if (event instanceof ClientChatReceivedEvent.System s && s.isOverlay()) {
            event.setMessage(EmojiText.convert(event.getMessage()).component());
        } else {
            event.setMessage(ChatReactionTracker.onOtherChat(event.getMessage()));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onChatSent(ClientChatEvent event) {
        String msg = event.getMessage();
        if (msg == null || msg.startsWith("/")) return;
        String expanded = EmojiText.expandShortcodes(msg);
        if (!expanded.equals(msg)) event.setMessage(expanded);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ChatReactionTracker.reset();
    }
}
