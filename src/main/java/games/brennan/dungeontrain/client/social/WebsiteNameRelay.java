package games.brennan.dungeontrain.client.social;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * The relay half of the Show my name on the website switch ({@code ClientDisplayConfig.isNameShownOnWebsite()}).
 *
 * <p>{@code POST /website-name {uuid, name, on}} tells the relay whether the dungeon.training reviews page
 * may show this player's name next to their survey answer; off, they appear as an anonymous rider. The
 * name is sent because a survey answer reaches the relay (via Discord) carrying only the player's name. Sent on
 * every login as well as on a change (and on a content-mode change, since Kid mode always hides it), so
 * the relay always holds this player's real setting even if someone else wrote to it.</p>
 *
 * <p>Nothing is sent without the internet connection consent. An older relay answers 404, which is
 * ignored. Resolves off-thread and never throws. Same shape as {@link PublicPostsRelay}.</p>
 */
public final class WebsiteNameRelay {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final String SET_PATH = "/website-name";

    private WebsiteNameRelay() {}

    /** Send the current switch to the relay. Fire and forget. */
    public static void syncNow() {
        String uuid = ownUuid();
        if (uuid == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        String name = ownName();
        if (name != null) body.addProperty("name", name);
        body.addProperty("on", ClientDisplayConfig.isNameShownOnWebsite());
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + SET_PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenAccept(r -> {
                    if (r.statusCode() / 100 != 2) {
                        LOGGER.debug("[DungeonTrain] website-name answered {}", r.statusCode());
                    }
                })
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] website-name failed: {}", t.toString());
                    return null;
                });
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] website-name failed: {}", e.toString());
        }
    }

    /** This player's Minecraft name, or null when it can't be read. */
    @Nullable
    private static String ownName() {
        Minecraft mc = Minecraft.getInstance();
        String name = mc != null && mc.getUser() != null ? mc.getUser().getName() : null;
        return name == null || name.isBlank() ? null : name;
    }

    /** This player's uuid without dashes, or null without the internet connection consent. */
    @Nullable
    private static String ownUuid() {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !DiscordPresenceClientConfig.isGranted()) return null;
        return uuid.toString().replace("-", "");
    }
}
