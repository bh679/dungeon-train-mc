package games.brennan.dungeontrain.event.upcoming;

import games.brennan.dungeontrain.client.version.compare.UpdatePage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Map;

/**
 * The chat line for one countdown stage:
 * {@code [Event] <title> starts in 1 hour. [Join on Twitch] [Details]}. The title and join label
 * are the relay's English, shown verbatim; Details opens the update page, which lists the event.
 *
 * <p>Built on the server and sent whole, so every key carries its English as a fallback: a client
 * on an older Dungeon Train that predates these keys shows the English, not the raw key.</p>
 */
public final class EventCountdownText {

    /** The update page's event purple. */
    private static final TextColor EVENT_PURPLE = TextColor.fromRgb(0xB57BFF);

    static final String PREFIX = "chat.dungeontrain.event.";

    /** Each key's en_us text, sent as its fallback. {@code EventCountdownTextTest} keeps them equal. */
    static final Map<String, String> ENGLISH = Map.of(
        PREFIX + "prefix", "[Event]",
        PREFIX + "in_24h", "%s starts in 24 hours.",
        PREFIX + "in_1h", "%s starts in 1 hour.",
        PREFIX + "in_10m", "%s starts in 10 minutes!",
        PREFIX + "now", "%s is starting now!",
        PREFIX + "join", "Join",
        PREFIX + "details", "Details");

    private EventCountdownText() {}

    public static Component line(UpcomingEvent event, EventCountdown.Stage stage) {
        MutableComponent title = Component.literal(event.title()).withStyle(ChatFormatting.BOLD);
        MutableComponent line = Component.empty()
            .append(text("prefix").withStyle(Style.EMPTY.withColor(EVENT_PURPLE).withBold(true)))
            .append(" ")
            .append(text(stage.key, title));
        if (event.url() != null) {
            Component label = event.joinLabel() != null ? Component.literal(event.joinLabel()) : text("join");
            line.append(" ").append(link(label, event.url()));
        }
        return line.append(" ").append(link(text("details"), UpdatePage.BASE_URL));
    }

    private static MutableComponent text(String name, Object... args) {
        String key = PREFIX + name;
        return Component.translatableWithFallback(key, ENGLISH.get(key), args);
    }

    private static Component link(Component label, String url) {
        return Component.literal("[").append(label).append("]").withStyle(Style.EMPTY
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
    }
}
