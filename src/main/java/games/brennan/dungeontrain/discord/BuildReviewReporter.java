package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * Announces the developer's verdict on a submitted build, on the same Discord channel its submission
 * was announced on — so the build-submissions thread of record shows the conversation beside the ask.
 *
 * <p>Accept, feedback and resubmit are announced; a decline is between the reviewer and the author,
 * who reads it in My Builds. Posted only after the relay has taken the verdict, never before, and
 * routed exactly as {@link BuildSubmitReporter} routes a submit: release builds to the dedicated
 * channel, dev builds to the dev channel. Best-effort: nothing here can fail the review.</p>
 */
public final class BuildReviewReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Embed bar colours — each verdict's tile-border colour, so the post and the tile read as one fact. */
    static final int EMBED_ACCEPTED = 0x55FF55;
    static final int EMBED_FEEDBACK = 0xFFFF55;
    static final int EMBED_RESUBMIT = 0xFFAA00;
    /** Kept for the accept, which is what this class first announced. */
    static final int EMBED_COLOR = EMBED_ACCEPTED;

    /** Whether a verdict is one the channel hears about. */
    public static boolean announces(String review) {
        return BuilderReviewState.ACCEPTED.equals(review) || BuilderReviewState.FEEDBACK.equals(review)
                || BuilderReviewState.RESUBMIT.equals(review);
    }
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
        postSafely(reviewer, relayId, ownerName, kind, subKind, buildName, comment, render,
                BuilderReviewState.ACCEPTED, "", "");
    }

    /**
     * As above for any announced verdict. {@code version}/{@code op} are the resubmit rule, blank otherwise.
     */
    public static void postSafely(ServerPlayer reviewer, int relayId, String ownerName, String kind, String subKind,
                                  String buildName, String comment, byte[] render, String review,
                                  String version, String op) {
        try {
            post(reviewer, relayId, ownerName, kind, subKind, buildName, comment, render, review, version, op);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] build-review Discord post failed: {}", t.toString());
        }
    }

    private static void post(ServerPlayer reviewer, int relayId, String ownerName, String kind, String subKind,
                             String buildName, String comment, byte[] render, String review, String version, String op) {
        String verdict = BuilderReviewState.of(review);
        if (!announces(verdict)) return;
        String who = reviewer.getGameProfile().getName();
        String title = title(who, buildName, ownerName, verdict, version, op);
        String description = description(comment, verdict);
        List<DeathField> fields = BuildSubmitReporter.fields(relayId, kind, subKind);
        byte[] png = render == null || render.length == 0 ? null : render;
        LOGGER.info("[DungeonTrain] {} {} '{}' (relay #{}) — posting announcement{}.",
                who, verb(verdict), buildName, relayId, png == null ? " without a render" : "");
        DiscordService.get().postReportTopLevel(reviewer, title, description, fields, png, PHOTO_FILENAME,
                colour(verdict), DungeonTrain.buildSubmitWebhookOverride());
    }

    /** "Brennan accepted brick_cabin by Ada" — a nameless build or owner is still announced. */
    static String title(String reviewer, String buildName, String ownerName) {
        return title(reviewer, buildName, ownerName, BuilderReviewState.ACCEPTED, "", "");
    }

    /**
     * The headline per verdict: "Brennan accepted X by Ada", "Brennan sent feedback on X by Ada",
     * "Brennan sent X by Ada back for Dungeon Train 0.1130.0 or above".
     */
    static String title(String reviewer, String buildName, String ownerName, String review, String version, String op) {
        String name = buildName == null || buildName.isBlank() ? "a build" : buildName;
        String by = ownerName == null || ownerName.isBlank() ? "" : " by " + ownerName;
        return switch (BuilderReviewState.of(review)) {
            case BuilderReviewState.FEEDBACK -> reviewer + " sent feedback on " + name + by;
            case BuilderReviewState.RESUBMIT -> reviewer + " sent " + name + by + " back for Dungeon Train " + ruleText(version, op);
            default -> reviewer + " accepted " + name + by;
        };
    }

    /** "0.1130.0 or above" — the resubmit rule in plain English, for a channel read in every language. */
    static String ruleText(String version, String op) {
        String v = version == null || version.isBlank() ? "a newer version" : version.strip();
        return switch (BuilderReviewState.opOf(op)) {
            case BuilderReviewState.OP_EXACT -> v + " exactly";
            case BuilderReviewState.OP_LTE -> v + " or below";
            default -> v + " or above";
        };
    }

    static int colour(String review) {
        return switch (BuilderReviewState.of(review)) {
            case BuilderReviewState.FEEDBACK -> EMBED_FEEDBACK;
            case BuilderReviewState.RESUBMIT -> EMBED_RESUBMIT;
            default -> EMBED_ACCEPTED;
        };
    }

    private static String verb(String review) {
        return switch (BuilderReviewState.of(review)) {
            case BuilderReviewState.FEEDBACK -> "sent feedback on";
            case BuilderReviewState.RESUBMIT -> "sent back";
            default -> "accepted";
        };
    }

    /** The reviewer's comment, or a line saying there was none — the embed never has an empty body. */
    static String description(String comment) {
        return description(comment, BuilderReviewState.ACCEPTED);
    }

    static String description(String comment, String review) {
        if (comment == null || comment.isBlank()) {
            return switch (BuilderReviewState.of(review)) {
                case BuilderReviewState.FEEDBACK -> "Sent back with feedback — no comment attached.";
                case BuilderReviewState.RESUBMIT -> "Sent back to be re-saved and re-submitted from that version.";
                default -> "Accepted into the game — it can now ride the train.";
            };
        }
        String c = comment.strip();
        return c.length() <= COMMENT_MAX ? c : c.substring(0, COMMENT_MAX).stripTrailing() + "…";
    }
}
