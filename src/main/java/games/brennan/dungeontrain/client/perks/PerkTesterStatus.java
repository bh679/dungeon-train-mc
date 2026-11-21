package games.brennan.dungeontrain.client.perks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
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
import java.util.UUID;

/**
 * Whether this player is an approved perks tester, which shows the title screen's Shift-revealed
 * perks test world button ({@code DevQuickWorldHandler}). The relay keeps the list (admin-managed)
 * and answers {@code GET /perk-testers/check?uuid=&name=} with {@code {"tester": true|false}}.
 *
 * <p>Modelled on {@code SupporterStatus}: <b>only with network consent</b> (the name and uuid leave
 * the machine), asked at most once every ten minutes, and a failed fetch keeps the last answer. With
 * no answer nobody is a tester, so a relay without the endpoint shows the button to no one.</p>
 */
public final class PerkTesterStatus {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long TTL_MS = 10 * 60_000L;

    /** Dev builds only: {@code -Ddungeontrain.perktester=true|false} answers without asking. */
    private static final String DEV_OVERRIDE_PROPERTY = "dungeontrain.perktester";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static volatile boolean tester = false;
    private static volatile long fetchedAt = 0L;

    private PerkTesterStatus() {}

    /** Whether the relay has said this player is an approved perks tester. */
    public static boolean isTester() {
        Boolean forced = devOverride();
        if (forced != null) return forced;
        return consented() && tester;
    }

    /** Ask the relay if the answer is older than ten minutes (or missing). Off-thread, no callback. */
    public static void refresh() {
        if (devOverride() != null || !consented()) return;
        if (fetchedAt != 0L && System.currentTimeMillis() - fetchedAt < TTL_MS) return;
        User user = Minecraft.getInstance().getUser();
        if (user == null || user.getProfileId() == null) return;
        // Stamped at request time: a failed fetch never calls back, so this alone spaces retries.
        fetchedAt = System.currentTimeMillis();
        fetch(user.getProfileId(), user.getName());
    }

    /** The relay's answer, from its JSON body; null when the body isn't one. */
    static Boolean parse(String body) {
        if (body == null || body.isBlank()) return null;
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("tester") || !o.get("tester").isJsonPrimitive()) return null;
        return o.get("tester").getAsBoolean();
    }

    private static void fetch(UUID uuid, String name) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/perk-testers/check?uuid="
                    + uuid.toString().replace("-", "")
                    + (name == null || name.isBlank() ? "" : "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
                try {
                    if (err != null || resp.statusCode() / 100 != 2) {
                        LOGGER.debug("[DungeonTrain] perk tester check failed: {}",
                                err != null ? err.toString() : "HTTP " + resp.statusCode());
                        return;
                    }
                    Boolean answer = parse(resp.body());
                    if (answer != null) tester = answer;
                } catch (Throwable t) {
                    LOGGER.debug("[DungeonTrain] perk tester check parse failed: {}", t.toString());
                }
            });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] perk tester check failed to start: {}", t.toString());
        }
    }

    private static Boolean devOverride() {
        if (!DungeonTrain.isDevBuild()) return null;
        String v = System.getProperty(DEV_OVERRIDE_PROPERTY);
        if (v == null || v.isBlank()) return null;
        return Boolean.parseBoolean(v.trim());
    }

    private static boolean consented() {
        try {
            return DiscordPresenceClientConfig.isGranted();
        } catch (Throwable t) {
            return false;
        }
    }
}
