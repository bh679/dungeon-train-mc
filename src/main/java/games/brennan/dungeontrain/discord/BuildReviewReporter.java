package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * Announces a build the developer accepted into the game, on the same Discord channel its submission
 * was announced on — so the build-submissions thread of record shows the decision beside the ask.
 *
 * <p>Only accepts are announced. Feedback and a decline are between the reviewer and the author, and
 * they read them in My Builds. Posted only after the relay has taken the verdict, never before, and
 * routed exactly as {@link BuildSubmitReporter} routes a submit: release builds to the dedicated
 * channel, dev builds to the dev channel. Best-effort: nothing here can fail the review.</p>
 */
public final class BuildReviewReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Embed bar colour — the green of an accepted tile's border, so the two read as one fact. */
    static final int EMBED_COLOR = 0x55FF55;
    static final String PHOTO_FILENAME = "build.png";
    /** Characters of the comment shown; the relay keeps the whole thing for the author. */
    static final int COMMENT_MAX = 600;

    private BuildReviewReporter() {}

    /**
     * Post the announcement; never throws into the caller.
     *
     * @param render PNG bytes from the client, or null/empty when it had no picture to send
     */
    public static void postSafely(ServerPlayer reviewer, int relayId, String ownerName, String kind, String subKind,
                                  String buildName, String comment, byte[] render) {
        try {
            post(reviewer, relayId, ownerName, kind, subKind, buildName, comment, render);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] build-accepted Discord post failed: {}", t.toString());
        }
    }

    private static void post(ServerPlayer reviewer, int relayId, String ownerName, String kind, String subKind,
                             String buildName, String comment, byte[] render) {
        String who = reviewer.getGameProfile().getName();
        String title = title(who, buildName, ownerName);
        String description = description(comment);
        List<DeathField> fields = BuildSubmitReporter.fields(relayId, kind, subKind);
        byte[] png = render == null || render.length == 0 ? null : render;
        LOGGER.info("[DungeonTrain] {} accepted '{}' (relay #{}) — posting announcement{}.",
                who, buildName, relayId, png == null ? " without a render" : "");
        DiscordService.get().postReportTopLevel(reviewer, title, description, fields, png, PHOTO_FILENAME,
                EMBED_COLOR, DungeonTrain.buildSubmitWebhookOverride());
    }

    /** "Brennan accepted brick_cabin by Ada" — a nameless build or owner is still announced. */
    static String title(String reviewer, String buildName, String ownerName) {
        String name = buildName == null || buildName.isBlank() ? "a build" : buildName;
        String by = ownerName == null || ownerName.isBlank() ? "" : " by " + ownerName;
        return reviewer + " accepted " + name + by;
    }

    /** The reviewer's comment, or a line saying there was none — the embed never has an empty body. */
    static String description(String comment) {
        if (comment == null || comment.isBlank()) return "Accepted into the game — it can now ride the train.";
        String c = comment.strip();
        return c.length() <= COMMENT_MAX ? c : c.substring(0, COMMENT_MAX).stripTrailing() + "…";
    }
}
