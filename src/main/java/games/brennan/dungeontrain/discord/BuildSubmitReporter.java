package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.SubmitNote;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Announces a build submitted for review on Discord, with the picture the client took of it.
 *
 * <p>Posted once the relay has accepted the publish, never before — an announcement for a build
 * the relay refused would send a reviewer looking for something that is not there. Release builds
 * route it to the dedicated build-submissions channel ({@link DungeonTrain#buildSubmitWebhookOverride});
 * dev builds fall through to the dev channel like every other report, so testing never pings the
 * community. Best-effort: nothing here can fail the submit.</p>
 */
public final class BuildSubmitReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Embed bar colour — the builder's amber, distinct from deaths (red), echoes (grey-blue). */
    static final int EMBED_COLOR = 0xE0A030;
    static final String PHOTO_FILENAME = "build.png";
    /** Characters of each answer shown; the relay keeps the full note for the reviewer. */
    static final int ANSWER_MAX = 400;

    private BuildSubmitReporter() {}

    /**
     * Post the announcement; never throws into the caller.
     *
     * @param render PNG bytes from the client, or null/empty when it had no picture to send
     */
    public static void postSafely(ServerPlayer player, int relayId, String kind, String subKind,
                                  String buildName, SubmitNote note, byte[] render) {
        try {
            post(player, relayId, kind, subKind, buildName, note, render);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] build-submit Discord post failed: {}", t.toString());
        }
    }

    private static void post(ServerPlayer player, int relayId, String kind, String subKind,
                             String buildName, SubmitNote note, byte[] render) {
        String author = player.getGameProfile().getName();
        String title = title(author, buildName);
        String description = description(note);
        List<DeathField> fields = fields(relayId, kind, subKind);
        byte[] png = render == null || render.length == 0 ? null : render;
        LOGGER.info("[DungeonTrain] {} submitted '{}' (relay #{}) — posting announcement{}.",
                author, buildName, relayId, png == null ? " without a render" : "");
        DiscordService.get().postReportTopLevel(player, title, description, fields, png, PHOTO_FILENAME,
                EMBED_COLOR, DungeonTrain.buildSubmitWebhookOverride());
    }

    /** "Ada submitted brick_cabin for review" — a nameless build is still announced. */
    static String title(String author, String buildName) {
        String name = buildName == null || buildName.isBlank() ? "a build" : buildName;
        return author + " submitted " + name + " for review";
    }

    /** The author's answers, one bold-labelled line each; a build with none says so. */
    static String description(SubmitNote note) {
        SubmitNote n = note == null ? SubmitNote.EMPTY : note;
        List<String> lines = new ArrayList<>();
        if (!n.redstone().isEmpty()) lines.add("**Redstone:** " + clip(n.redstone()));
        if (!n.loot().isEmpty()) lines.add("**Loot:** " + clip(n.loot()));
        if (!n.notes().isEmpty()) lines.add("**Notes:** " + clip(n.notes()));
        return lines.isEmpty() ? "No notes from the author." : String.join("\n", lines);
    }

    /** Where to find it: the relay id, and what kind of build it is. */
    static List<DeathField> fields(int relayId, String kind, String subKind) {
        List<DeathField> fields = new ArrayList<>();
        fields.add(new DeathField("Build", "#" + relayId));
        String what = kind == null ? "" : kind;
        if (subKind != null && !subKind.isEmpty()) what = what.isEmpty() ? subKind : what + " / " + subKind;
        if (!what.isEmpty()) fields.add(new DeathField("Kind", what));
        return fields;
    }

    private static String clip(String s) {
        return s.length() <= ANSWER_MAX ? s : s.substring(0, ANSWER_MAX).stripTrailing() + "…";
    }
}
