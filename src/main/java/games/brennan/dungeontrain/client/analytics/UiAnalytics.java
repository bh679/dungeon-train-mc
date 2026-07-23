package games.brennan.dungeontrain.client.analytics;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Fire-and-forget UI analytics for the Support funnel: the title screen's "Support the Mod" /
 * Discord / Patreon buttons and everything on the {@code SupportScreen} — clicks, whether the
 * player followed through past the vanilla ConfirmLinkScreen, and time spent on the page. Read
 * back by the data explorer's Finances page (dp-relay {@code revenue-report.js}).
 *
 * <p>These fire at the main menu where no Minecraft server exists, so this talks to the relay
 * directly over HTTPS ({@link DungeonTrain#relayBaseUrl()} → {@code POST /telemetry/ui-event}),
 * mirroring {@code RelayChatClient}. Every call is gated on the DiscordPresence network-access
 * consent ({@link DiscordPresenceClientConfig#isGranted()}) and is best-effort: fully async,
 * never throws, no retry — losing an analytics event to a network blip is fine (unlike
 * gameplay telemetry, which rides the durable server-side RelayOutbox).</p>
 *
 * <p>Enum values ({@code surface}/{@code target}/{@code action}) are whitelisted relay-side
 * (dp-relay {@code ui-events.js}) — an unknown value is rejected with a 400, so additions must
 * land on both sides.</p>
 */
public final class UiAnalytics {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Surfaces
    public static final String SURFACE_SUPPORT_PAGE = "support_page";
    public static final String SURFACE_TITLE_SCREEN = "title_screen";
    // Targets
    public static final String TARGET_PAGE = "page";
    public static final String TARGET_SUPPORT = "support";
    public static final String TARGET_DONATE = "donate";
    public static final String TARGET_PATREON = "patreon";
    public static final String TARGET_AFFILIATE = "affiliate";
    public static final String TARGET_DISCORD = "discord";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1; avoids h2c against a bare-Node relay (matches RelayChatClient)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private UiAnalytics() {}

    /** A button/link was pressed (before any confirm screen). */
    public static void click(String surface, String target) {
        send(surface, target, "click", -1);
    }

    /** The ConfirmLinkScreen resolved — {@code yes} means the external link actually opened. */
    public static void confirm(String surface, String target, boolean yes) {
        send(surface, target, yes ? "confirm_yes" : "confirm_no", -1);
    }

    /** A tracked page was opened (fire once per visit — from the screen's constructor, not init()). */
    public static void pageOpen(String surface) {
        send(surface, TARGET_PAGE, "open", -1);
    }

    /** A tracked page was closed after {@code durationMs} on it (fire once per visit). */
    public static void pageTime(String surface, long durationMs) {
        send(surface, TARGET_PAGE, "page_time", Math.max(0, durationMs));
    }

    /** Consent-gate, build, and POST one event. Never throws; failures are debug-logged only. */
    private static void send(String surface, String target, String action, long durationMs) {
        try {
            if (!DiscordPresenceClientConfig.isGranted()) {
                return; // no network consent — no analytics, full stop
            }
            Minecraft mc = Minecraft.getInstance();
            UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
            if (uuid == null) {
                return; // relay requires a uuid; a launcher without one just isn't counted
            }
            String player = mc.getUser() != null ? mc.getUser().getName() : null;
            JsonObject payload = buildPayload(
                    noDashes(uuid), player, VersionInfo.VERSION, surface, target, action, durationMs);
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(DungeonTrain.relayBaseUrl() + "/telemetry/ui-event"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(t -> {
                        LOGGER.debug("UiAnalytics: {} {}/{} failed: {}", action, surface, target, t.toString());
                        return null;
                    });
        } catch (Throwable t) {
            LOGGER.debug("UiAnalytics: send failed", t);
        }
    }

    /**
     * The {@code /telemetry/ui-event} payload (see dp-relay {@code ui-events.js}). Pure — no
     * Minecraft bootstrap — so it unit-tests directly. {@code durationMs < 0} omits the field
     * (it is only valid, and only required, on the {@code page_time} action).
     */
    static JsonObject buildPayload(String uuid, String player, String modVersion,
                                   String surface, String target, String action, long durationMs) {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid);
        if (player != null && !player.isBlank()) {
            payload.addProperty("player", player);
        }
        if (modVersion != null && !modVersion.isBlank()) {
            payload.addProperty("modVersion", modVersion);
        }
        payload.addProperty("surface", surface);
        payload.addProperty("target", target);
        payload.addProperty("action", action);
        if (durationMs >= 0) {
            payload.addProperty("durationMs", durationMs);
        }
        return payload;
    }

    private static String noDashes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
