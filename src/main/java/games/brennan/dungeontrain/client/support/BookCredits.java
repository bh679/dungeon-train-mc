package games.brennan.dungeontrain.client.support;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DonationSummaryCache;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * How many Priority Books this player has waiting: the thank-you a tier gift carries, one per $10 AUD,
 * each one making one of their own books turn up far more often in other players' trains.
 *
 * <p>Read from the relay's {@code GET /books/credits?uuid=&name=} — the player's own wallet, keyed by
 * the name the gift carried and bound to their uuid on first spend. <b>Only with network consent</b>:
 * both identifiers leave the machine, and without consent no book is shared anyway, so every Priority
 * Book surface simply stays hidden ({@link #current()} answers 0).</p>
 *
 * <p>Cached for a minute and refreshed by the screens that show it. A spend sent from this client
 * lowers the cached count at once ({@link #spentOne()}): the relay answers through the server's outbox
 * some seconds later, and the toggle should not keep offering a book that is already on its way.
 * Never throws; a failed fetch leaves the last known value.</p>
 *
 * <p>Boosts are counted in tenths: one Priority Book is ten boosts on other players' books
 * ({@link #boostsLeft()}). They are read from the relay's {@code boostsLeft}; a relay too old to send
 * it answers 0, which keeps the Boost control hidden rather than offering one that would be refused.</p>
 */
public final class BookCredits {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TTL_MS = 60_000L;
    /** One Priority Book is ten boosts. */
    public static final int BOOSTS_PER_BOOK = 10;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** -1 until the relay has answered once this session. */
    private static volatile int known = -1;
    /** Boosts left (tenths of a Priority Book); -1 until a relay that knows about boosts has answered. */
    private static volatile int knownBoosts = -1;
    private static volatile long fetchedAt = 0L;
    private static volatile boolean inFlight = false;

    private BookCredits() {}

    /**
     * Priority Books waiting, or 0 when unknown, none, or the player has not consented. Falls back to
     * the donation summary's {@code you.bookCredits} until the dedicated read has answered.
     */
    public static int current() {
        if (!consented()) return 0;
        int k = known;
        if (k >= 0) return k;
        DonationSummaryClient.Summary s = DonationSummaryCache.get();
        return s != null ? Math.max(0, s.youBookCredits()) : 0;
    }

    /** Fetch if the cached count is older than a minute. {@code onChange} runs on the client thread. */
    public static void refresh(Runnable onChange) {
        if (!consented() || inFlight) return;
        if (known >= 0 && System.currentTimeMillis() - fetchedAt < TTL_MS) return;
        User user = Minecraft.getInstance().getUser();
        if (user == null || user.getProfileId() == null) return;
        try {
            String url = DungeonTrain.relayBaseUrl() + "/books/credits?uuid="
                    + user.getProfileId().toString().replace("-", "")
                    + "&name=" + URLEncoder.encode(user.getName(), StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            inFlight = true;
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
                inFlight = false;
                try {
                    if (err != null || resp.statusCode() / 100 != 2) return;
                    int balance = parseBalance(resp.body());
                    if (balance < 0) return;
                    int before = known;
                    int boostsBefore = knownBoosts;
                    known = balance;
                    knownBoosts = parseBoostsLeft(resp.body());
                    fetchedAt = System.currentTimeMillis();
                    boolean changed = balance != before || knownBoosts != boostsBefore;
                    if (changed && onChange != null) Minecraft.getInstance().execute(onChange);
                } catch (Throwable t) {
                    LOGGER.debug("[DungeonTrain] book credits parse failed: {}", t.toString());
                }
            });
        } catch (Throwable t) {
            inFlight = false;
            LOGGER.debug("[DungeonTrain] book credits request failed to start: {}", t.toString());
        }
    }

    /** A spend just left this client: show one fewer now, and re-read the relay next time. */
    public static void spentOne() {
        int k = current();
        known = Math.max(0, k - 1);
        if (knownBoosts >= 0) knownBoosts = Math.max(0, knownBoosts - BOOSTS_PER_BOOK);
        fetchedAt = 0L;
    }

    /**
     * Boosts this player can give other players' books: tenths of a Priority Book. 0 when unknown,
     * none, no consent, or the relay does not know about boosts.
     */
    public static int boostsLeft() {
        if (!consented()) return 0;
        return Math.max(0, knownBoosts);
    }

    /**
     * Boosts left for showing a count when the relay may not have said: the relay's own figure, or the
     * whole Priority Books times ten. Never gates a control; {@link #boostsLeft()} does that.
     */
    public static int boostsLeftOrEstimate() {
        if (!consented()) return 0;
        return knownBoosts >= 0 ? knownBoosts : current() * BOOSTS_PER_BOOK;
    }

    /** A boost just left this client: a tenth fewer now (a whole book once ten are gone), re-read next time. */
    public static void boostedOne() {
        int b = boostsLeft();
        if (b <= 0) return;
        knownBoosts = b - 1;
        known = knownBoosts / BOOSTS_PER_BOOK;
        fetchedAt = 0L;
    }

    /** {@code balance} from a {@code /books/credits} body, or -1 when it is not a usable answer. */
    static int parseBalance(String body) {
        if (body == null || body.isBlank()) return -1;
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return -1;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return -1;
        JsonElement b = o.get("balance");
        if (b == null || !b.isJsonPrimitive()) return -1;
        return Math.max(0, b.getAsInt());
    }

    /** {@code boostsLeft} from a {@code /books/credits} body, or -1 when the relay did not send it. */
    static int parseBoostsLeft(String body) {
        if (body == null || body.isBlank()) return -1;
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return -1;
        JsonElement b = root.getAsJsonObject().get("boostsLeft");
        if (b == null || !b.isJsonPrimitive()) return -1;
        try {
            return Math.max(0, b.getAsInt());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static boolean consented() {
        try {
            return DiscordPresenceClientConfig.isGranted();
        } catch (Throwable t) {
            return false;
        }
    }
}
