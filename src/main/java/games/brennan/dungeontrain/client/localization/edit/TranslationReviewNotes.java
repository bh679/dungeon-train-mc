package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What reviewers have written back to this player, held for the editor to render.
 *
 * <p>A translation is rejected in a language the reviewer usually cannot read, on grounds the
 * translator can rarely guess — a lost {@code %s}, a register that does not fit, a line that drifted
 * from its English. Before this, all any of that reached them as was a red badge. The reply is
 * fetched once per editor session ({@link TranslationSubmissionsClient#fetchNotes}) and shown in
 * the three places the translator is already looking: against the string in the working list, above
 * the box when they open it, and as a count on the way in.</p>
 *
 * <p>Unread is tracked by the newest {@code noteTs} the player has been shown, in one small file
 * beside the override layers. A timestamp rather than a set of ids: the question the prompt asks is
 * "has anything been said since I last looked", and one number answers it without a second body of
 * state to keep in step with the relay's.</p>
 *
 * <p>All best-effort: no notes, an unreachable relay or an unreadable marker file simply mean no
 * markers and no prompt.</p>
 */
public final class TranslationReviewNotes {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String FILE_NAME = "notes-seen.txt";

    /** {@link TranslationSubmissionsClient.ReviewNote#key()} → the note. Replaced per fetch. */
    private static Map<String, TranslationSubmissionsClient.ReviewNote> byUnit = Map.of();
    private static boolean fetchedThisSession;
    /** The newest noteTs the player has been shown; {@code null} until read off disk. */
    private static Long seenUpTo;

    private TranslationReviewNotes() {}

    /** The reply on {@code unit}, or null — what the list marks and the edit screen prints. */
    public static synchronized TranslationSubmissionsClient.ReviewNote forUnit(TranslationUnit unit) {
        if (unit == null || byUnit.isEmpty()) {
            return null;
        }
        return byUnit.get((unit.type() == TranslationUnit.Type.BOOK ? "book|" : "lang|") + unit.id());
    }

    /**
     * The reply on {@code unit} as one line of text — {@code <reviewer>: <what they wrote>} — or
     * null. What the list draws under a row, so the reply is readable where the translator is
     * looking at what they sent rather than only inside the editor.
     */
    public static synchronized String textFor(TranslationUnit unit) {
        TranslationSubmissionsClient.ReviewNote note = forUnit(unit);
        if (note == null) {
            return null;
        }
        String who = note.noteBy() == null || note.noteBy().isBlank() ? "admin" : note.noteBy();
        return who + ": " + note.note();
    }

    /** How many replies are newer than the last the player was shown. */
    public static synchronized int unreadCount() {
        long seen = seenUpTo();
        int n = 0;
        for (TranslationSubmissionsClient.ReviewNote note : byUnit.values()) {
            if (note.noteTs() > seen) {
                n++;
            }
        }
        return n;
    }

    /**
     * Fetch once per session, then hand the result to {@code onLoaded} (on the render thread).
     *
     * <p>Once per session for the same reason approvals are: a reply is not urgent, and the
     * moments that matter — opening the title screen, opening the editor — are both covered by
     * one. {@link #refresh} is the way to ask again.</p>
     */
    public static void fetchOnce(Runnable onLoaded) {
        synchronized (TranslationReviewNotes.class) {
            if (fetchedThisSession) {
                if (onLoaded != null) {
                    onLoaded.run();
                }
                return;
            }
            fetchedThisSession = true;
        }
        refresh(onLoaded);
    }

    /** Ask again regardless of the once-per-session guard. No-throw. */
    public static void refresh(Runnable onLoaded) {
        TranslationSubmissionsClient.fetchNotes((notes) -> {
            store(notes);
            if (onLoaded != null) {
                onLoaded.run();
            }
        });
    }

    /**
     * Mark everything currently held as read — called when the editor opens, which is the moment
     * the player can actually act on a reply.
     */
    public static synchronized void markAllSeen() {
        long newest = seenUpTo();
        for (TranslationSubmissionsClient.ReviewNote note : byUnit.values()) {
            newest = Math.max(newest, note.noteTs());
        }
        if (newest > seenUpTo()) {
            seenUpTo = newest;
            write(newest);
        }
    }

    private static synchronized void store(List<TranslationSubmissionsClient.ReviewNote> notes) {
        Map<String, TranslationSubmissionsClient.ReviewNote> map = new LinkedHashMap<>();
        for (TranslationSubmissionsClient.ReviewNote note : notes == null ? List.<TranslationSubmissionsClient.ReviewNote>of() : notes) {
            // Newest first from the relay, so the first note for a unit is the current one.
            map.putIfAbsent(note.key(), note);
        }
        byUnit = Map.copyOf(map);
    }

    private static long seenUpTo() {
        if (seenUpTo == null) {
            seenUpTo = read();
        }
        return seenUpTo;
    }

    private static long read() {
        try {
            Path file = file();
            if (file == null || !Files.isRegularFile(file)) {
                return 0L;
            }
            return Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void write(long value) {
        try {
            Path file = file();
            if (file == null) {
                return;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, Long.toString(value), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not record which replies were read — {}",
                e.toString());
        }
    }

    private static Path file() {
        try {
            return TranslationOverrideStore.root().resolve(FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    /** Test seam: forget everything held in memory. */
    static synchronized void resetForTesting() {
        byUnit = Map.of();
        fetchedThisSession = false;
        seenUpTo = null;
    }
}
