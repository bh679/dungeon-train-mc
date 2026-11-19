package games.brennan.dungeontrain.event.upcoming;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * {@code /dungeontrain debug event-countdown}: sends you all four countdown lines at once, for the
 * next event the update page lists (or a sample one), without waiting for the real moments.
 */
public final class EventCountdownDebug {

    private static final UpcomingEvent SAMPLE = new UpcomingEvent(
        "sample", "AshSeven goes for Everything Burrito", 0L, "https://twitch.tv/ashseven7", "Join on Twitch");

    private EventCountdownDebug() {}

    public static int preview(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player."));
            return 0;
        }
        UpcomingEventsClient.refreshIfStale(System.currentTimeMillis());
        UpcomingEvent event = next(UpcomingEventsClient.events(), System.currentTimeMillis() / 1000L);
        source.sendSuccess(() -> Component.literal("Countdown preview for '" + event.id() + "' ("
            + UpcomingEventsClient.events().size() + " event(s) read):"), false);
        for (EventCountdown.Stage stage : EventCountdown.Stage.values()) {
            EventCountdownTicker.send(player, EventCountdownText.line(event, stage), stage);
        }
        return 1;
    }

    /** The soonest event not yet started, else the sample. */
    static UpcomingEvent next(List<UpcomingEvent> events, long nowSec) {
        UpcomingEvent best = null;
        for (UpcomingEvent e : events) {
            if (e.unixSec() >= nowSec && (best == null || e.unixSec() < best.unixSec())) best = e;
        }
        return best != null ? best : SAMPLE;
    }
}
