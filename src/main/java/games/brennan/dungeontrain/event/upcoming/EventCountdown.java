package games.brennan.dungeontrain.event.upcoming;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which countdown lines are due: 24 hours, 1 hour, 10 minutes before an event, and as it starts.
 * Each stage is said once, and only shortly after its moment ({@link Stage#graceSec}), so a server
 * that starts 20 hours out never claims "in 24 hours" and a missed stage is skipped, not sent late.
 * Pure, so it is unit-tested.
 */
public final class EventCountdown {

    public enum Stage {
        DAY(24 * 3600, 30 * 60, "in_24h"),
        HOUR(3600, 5 * 60, "in_1h"),
        TEN_MIN(10 * 60, 2 * 60, "in_10m"),
        NOW(0, 10 * 60, "now");

        /** Seconds before the event's start this stage is said. */
        public final long beforeSec;
        /** How long after that moment it may still be said. */
        public final long graceSec;
        /** Lang key suffix under {@code chat.dungeontrain.event.}. */
        public final String key;

        Stage(long beforeSec, long graceSec, String key) {
            this.beforeSec = beforeSec;
            this.graceSec = graceSec;
            this.key = key;
        }
    }

    /** One line to send. */
    public record Due(UpcomingEvent event, Stage stage) {
        /** Identity in the sent set: the same event and stage are never said twice. */
        public String sentKey() {
            return event.id() + "@" + event.unixSec() + "#" + stage.name();
        }
    }

    private EventCountdown() {}

    /** The lines due at {@code nowSec} that {@code sent} does not already hold. */
    public static List<Due> due(List<UpcomingEvent> events, long nowSec, Set<String> sent) {
        List<Due> out = new ArrayList<>();
        for (UpcomingEvent event : events) {
            for (Stage stage : Stage.values()) {
                long at = event.unixSec() - stage.beforeSec;
                if (nowSec < at || nowSec >= at + stage.graceSec) continue;
                Due due = new Due(event, stage);
                if (!sent.contains(due.sentKey())) out.add(due);
            }
        }
        return List.copyOf(out);
    }
}
