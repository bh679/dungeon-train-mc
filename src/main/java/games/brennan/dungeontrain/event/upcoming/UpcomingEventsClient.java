package games.brennan.dungeontrain.event.upcoming;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The update page's event list, as this server last read it from dungeon.training. Fire-and-forget
 * GET of the static {@code events.json} every 15 minutes (the relay's own announcer reads it at the
 * same cadence), no-throw, own {@link HttpClient} like {@code DonationSummaryClient}.
 *
 * <p>Consent: an anonymous static file, no player data sent, so it is read regardless of the
 * network-consent setting (same as the donation summary's leaderboards).</p>
 *
 * <p>Dev builds may point it elsewhere with {@code -Ddungeontrain.eventsUrl=http://…} to test a
 * countdown without publishing an event.</p>
 */
public final class UpcomingEventsClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String EVENTS_URL = "https://dungeon.training/update/events.json";
    private static final String URL_PROPERTY = "dungeontrain.eventsUrl";
    private static final long REFRESH_INTERVAL_MS = 15L * 60L * 1000L;

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final AtomicLong LAST_ATTEMPT_MS = new AtomicLong(0L);
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    /** Warn once per run of failures, not every 15 minutes while offline. */
    private static final AtomicBoolean FAILING = new AtomicBoolean();
    private static volatile List<UpcomingEvent> events = List.of();

    private UpcomingEventsClient() {}

    /** The events last read; empty until the first answer. */
    public static List<UpcomingEvent> events() {
        return events;
    }

    /** Read the file again unless it was read recently. Returns at once; the answer lands later. */
    public static void refreshIfStale(long nowMs) {
        long last = LAST_ATTEMPT_MS.get();
        if (last != 0L && nowMs - last < REFRESH_INTERVAL_MS) return;
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        LAST_ATTEMPT_MS.set(nowMs);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, error) -> {
                        try {
                            if (error != null) {
                                failed(error.toString());
                            } else if (response.statusCode() != 200) {
                                failed("HTTP " + response.statusCode());
                            } else {
                                events = UpcomingEvent.parseAll(response.body());
                                FAILING.set(false);
                            }
                        } finally {
                            IN_FLIGHT.set(false);
                        }
                    });
        } catch (RuntimeException e) {
            IN_FLIGHT.set(false);
            failed(e.toString());
        }
    }

    /** Server stopped: forget what was read, so the next world reads afresh. */
    public static void reset() {
        LAST_ATTEMPT_MS.set(0L);
        events = List.of();
    }

    private static String url() {
        String override = System.getProperty(URL_PROPERTY);
        return override != null && !override.isBlank() && DungeonTrain.isDevBuild() ? override.strip() : EVENTS_URL;
    }

    private static void failed(String why) {
        // Keep the last good list: a blip must not drop an event mid-countdown.
        if (FAILING.compareAndSet(false, true)) {
            LOGGER.warn("[DungeonTrain] upcoming events lookup failed: {}", why);
        }
    }
}
