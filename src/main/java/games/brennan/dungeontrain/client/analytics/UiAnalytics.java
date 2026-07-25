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
 * <p>Also drives the death-screen donation funnel (dp-relay {@code deathfunnel-report.js}): every
 * page of {@code NarrativeDeathScreen} reports an {@code open} + a {@code page_time} tagged with a
 * {@code page} dimension (fall/deeds/gear/lives/survey/donate/platform/contribute), its Contribute /
 * Board-anew / Leave / "$"-chip buttons report {@code click}s, and each survey answer reports a
 * {@code survey_answer} carrying the game-defined {@code questionId} + chosen {@code score} (never
 * the free-text comment).</p>
 *
 * <p>Enum values ({@code surface}/{@code target}/{@code action}) — and the {@code page} dimension —
 * are whitelisted relay-side (dp-relay {@code ui-events.js}) — an unknown value is rejected with a
 * 400, so additions must land on both sides.</p>
 */
public final class UiAnalytics {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Surfaces
    public static final String SURFACE_SUPPORT_PAGE = "support_page";
    public static final String SURFACE_TITLE_SCREEN = "title_screen";
    // The death-screen "support the line" donation page. Must stay in lock-step with the relay's
    // ui-events.js SURFACES whitelist, or click/confirm events 400 silently.
    public static final String SURFACE_DEATH_SCREEN = "death_screen";
    // Targets
    public static final String TARGET_PAGE = "page";
    public static final String TARGET_SUPPORT = "support";
    public static final String TARGET_DONATE = "donate";
    public static final String TARGET_PATREON = "patreon";
    public static final String TARGET_AFFILIATE = "affiliate";
    public static final String TARGET_DISCORD = "discord";
    // Death-screen button targets (see NarrativeDeathScreen). Lock-step with ui-events.js TARGETS.
    public static final String TARGET_CONTRIBUTE = "contribute"; // "Contribute" opens the donate-options window
    public static final String TARGET_BOARD_ANEW = "board_anew";  // "Board anew" — start the next run
    public static final String TARGET_LEAVE = "leave";           // "Leave" — back to title / quit
    public static final String TARGET_CHIP = "chip";             // the "$" top-bar chip → donate page

    // Death-screen page identities (the {@code page} dimension on open / page_time / survey_answer).
    // Lock-step with ui-events.js PAGES. The seven paginated death-screen pages, plus the full-screen
    // Contribute (donate-options) window that layers over the DONATE page.
    public static final String PAGE_FALL = "fall";
    public static final String PAGE_DEEDS = "deeds";
    public static final String PAGE_GEAR = "gear";
    public static final String PAGE_LIVES = "lives";
    public static final String PAGE_SURVEY = "survey";
    public static final String PAGE_DONATE = "donate";
    public static final String PAGE_PLATFORM = "platform";
    public static final String PAGE_CONTRIBUTE = "contribute";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1; avoids h2c against a bare-Node relay (matches RelayChatClient)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private UiAnalytics() {}

    /** A button/link was pressed (before any confirm screen). */
    public static void click(String surface, String target) {
        send(surface, target, "click", -1, null, null, -1, -1);
    }

    /** The ConfirmLinkScreen resolved — {@code yes} means the external link actually opened. */
    public static void confirm(String surface, String target, boolean yes) {
        send(surface, target, yes ? "confirm_yes" : "confirm_no", -1, null, null, -1, -1);
    }

    /** A tracked page was opened (fire once per visit — from the screen's constructor, not init()). */
    public static void pageOpen(String surface) {
        send(surface, TARGET_PAGE, "open", -1, null, null, -1, -1);
    }

    /** A tracked page was closed after {@code durationMs} on it (fire once per visit). */
    public static void pageTime(String surface, long durationMs) {
        send(surface, TARGET_PAGE, "page_time", Math.max(0, durationMs), null, null, -1, -1);
    }

    /**
     * A tracked page identified by {@code page} was opened. Used by multi-page surfaces (the death
     * screen) where a bare {@code surface} isn't enough — {@code page} names which of the pages
     * (fall/deeds/gear/…) was viewed. Lock-step with ui-events.js PAGES.
     */
    public static void pageOpen(String surface, String page) {
        pageOpen(surface, page, null);
    }

    /**
     * As {@link #pageOpen(String, String)} but carrying the datapack-defined {@code questionId} for
     * a survey page — the death screen has one survey page per question, so {@code page="survey"}
     * alone can't tell them apart. Null/blank for non-survey pages. Lock-step with ui-events.js.
     */
    public static void pageOpen(String surface, String page, String questionId) {
        send(surface, TARGET_PAGE, "open", -1, page, questionId, -1, -1);
    }

    /** As {@link #pageTime(String, long)} but identifying which multi-page {@code page} was left. */
    public static void pageTime(String surface, String page, long durationMs) {
        pageTime(surface, page, null, durationMs);
    }

    /** As {@link #pageTime(String, String, long)} but carrying a survey page's {@code questionId}. */
    public static void pageTime(String surface, String page, String questionId, long durationMs) {
        send(surface, TARGET_PAGE, "page_time", Math.max(0, durationMs), page, questionId, -1, -1);
    }

    /**
     * A death-screen survey answer was submitted: {@code questionId} is the datapack-defined
     * question id, {@code score} the chosen rating on a 0..{@code scoreMax} scale. Game-defined
     * enums only — never the free-text comment. Fired once per question (see maybeSubmit).
     */
    public static void surveyAnswer(String surface, String questionId, int score, int scoreMax) {
        send(surface, TARGET_PAGE, "survey_answer", -1, PAGE_SURVEY, questionId, score, scoreMax);
    }

    /** Consent-gate, build, and POST one event. Never throws; failures are debug-logged only. */
    private static void send(String surface, String target, String action, long durationMs,
                             String page, String questionId, int score, int scoreMax) {
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
                    noDashes(uuid), player, VersionInfo.VERSION, surface, target, action, durationMs,
                    page, questionId, score, scoreMax);
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
     * The core {@code /telemetry/ui-event} payload without the death-screen extras — kept as a thin
     * overload so the existing callers/tests read unchanged. Delegates with no {@code page} and no
     * survey fields.
     */
    static JsonObject buildPayload(String uuid, String player, String modVersion,
                                   String surface, String target, String action, long durationMs) {
        return buildPayload(uuid, player, modVersion, surface, target, action, durationMs,
                null, null, -1, -1);
    }

    /**
     * The full {@code /telemetry/ui-event} payload (see dp-relay {@code ui-events.js}). Pure — no
     * Minecraft bootstrap — so it unit-tests directly. Optional fields are omitted when unset:
     * {@code durationMs < 0} (only valid on {@code page_time}); {@code page} null/blank; and the
     * survey fields (only carried on {@code survey_answer}): {@code questionId} null/blank,
     * {@code score < 0}, {@code scoreMax < 0}.
     */
    static JsonObject buildPayload(String uuid, String player, String modVersion,
                                   String surface, String target, String action, long durationMs,
                                   String page, String questionId, int score, int scoreMax) {
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
        if (page != null && !page.isBlank()) {
            payload.addProperty("page", page);
        }
        if (questionId != null && !questionId.isBlank()) {
            payload.addProperty("questionId", questionId);
        }
        if (score >= 0) {
            payload.addProperty("score", score);
        }
        if (scoreMax >= 0) {
            payload.addProperty("scoreMax", scoreMax);
        }
        return payload;
    }

    private static String noDashes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
