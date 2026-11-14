package games.brennan.dungeontrain.event.upcoming;

import games.brennan.dungeontrain.client.version.compare.UpdatePage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * The chat line for one countdown stage:
 * {@code [Event] <title> starts in 1 hour. [Join on Twitch] [Details]}. The title and join label
 * are the relay's English, shown verbatim; Details opens the update page, which lists the event.
 */
public final class EventCountdownText {

    /** The update page's event purple. */
    private static final TextColor EVENT_PURPLE = TextColor.fromRgb(0xB57BFF);

    private EventCountdownText() {}

    public static Component line(UpcomingEvent event, EventCountdown.Stage stage) {
        MutableComponent title = Component.literal(event.title()).withStyle(ChatFormatting.BOLD);
        MutableComponent line = Component.empty()
            .append(Component.translatable("chat.dungeontrain.event.prefix")
                .withStyle(Style.EMPTY.withColor(EVENT_PURPLE).withBold(true)))
            .append(" ")
            .append(Component.translatable("chat.dungeontrain.event." + stage.key, title));
        if (event.url() != null) {
            Component label = event.joinLabel() != null
                ? Component.literal(event.joinLabel())
                : Component.translatable("chat.dungeontrain.event.join");
            line.append(" ").append(link(label, event.url()));
        }
        return line.append(" ").append(link(Component.translatable("chat.dungeontrain.event.details"), UpdatePage.BASE_URL));
    }

    private static Component link(Component label, String url) {
        return Component.literal("[").append(label).append("]").withStyle(Style.EMPTY
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
    }
}
