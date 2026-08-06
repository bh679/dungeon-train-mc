package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Uploader for a completed "Death Note" curse. When the author of a pending Death Note dies
 * ({@code DeathNoteEvents}), the curse — now knowing the carriage the author died at — is submitted
 * to the relay's {@code /deathnotes/submit} endpoint so the named target can download it and, on
 * reaching that carriage in the same world, be hunted by an echo of the author.
 *
 * <p>Mirrors {@link SharedBookReporter}: a Gson body handed to the durable {@link RelayOutbox}
 * (at-least-once delivery, surviving relay outages / offline launches). The whole call is no-throw.</p>
 */
public final class DeathNoteReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DeathNoteReporter() {}

    /**
     * Build and fire the death-note submission. No-op on any error. Callers are already gated
     * (feature enabled + client network consent) by {@code DeathNoteGate}; this just does transport.
     *
     * @param authorId       the author (signer) UUID — sent dash-stripped, matching the other reporters
     * @param authorName     the author name (the echo is of this player)
     * @param targetName     the cursed player's name (first line of the book) — the relay's query key
     * @param targetUuid     the cursed player's dash-stripped UUID if known, else "" (name-matched)
     * @param deathCarriage  the carriage index the author died at (where the echo will lie in wait)
     * @param worldKey       this world's key (train generation seed) — scopes the curse to one world
     * @param authorSkinRef  optional pre-encoded skin ref for the echo, or "" (spawner encodes from uuid+name)
     * @param freePlay       whether the author died on a Free Play (cheated) run — the curse then only
     *                       spawns for a target who is also in Free Play (provenance match)
     */
    public static void submit(UUID authorId, String authorName, String targetName, String targetUuid,
                              int deathCarriage, String worldKey, String authorSkinRef, boolean freePlay) {
        try {
            if (authorId == null) return;
            String uuid = authorId.toString().replace("-", "");
            JsonObject payload = buildPayload(uuid, authorName, targetName, targetUuid,
                    deathCarriage, worldKey, authorSkinRef, freePlay);
            RelayOutbox.get().enqueue("/deathnotes/submit", payload.toString());
            LOGGER.debug("[DungeonTrain] DeathNote submit (target {}, carriage {}) queued to the relay outbox.",
                    targetName, deathCarriage);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] DeathNote submit failed to build: {}", t.toString());
        }
    }

    /**
     * Report that note {@code noteId}'s echo has spawned — flips the relay's one-way {@code used}
     * latch so the note is never served or spawned again. Durable + idempotent. No-throw.
     */
    public static void markUsed(int noteId) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("id", noteId);
            RelayOutbox.get().enqueue("/deathnotes/used", body.toString());
            LOGGER.debug("[DungeonTrain] DeathNote mark-used {} queued to the relay outbox.", noteId);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] DeathNote mark-used failed to build: {}", t.toString());
        }
    }

    /**
     * Report how a landed curse ended, from the TARGET's game — the other half of the author's story
     * loop (the relay serves it back on {@code /deathnotes/landed}). Write-once on the relay: the first
     * report wins, so a retry or a second death event can't rewrite the ending. Durable + no-throw.
     *
     * @param noteId  the relay note id the echo was spawned from (stamped on the echo at spawn)
     * @param outcome one of {@link #OUTCOME_ECHO_KILLED_TARGET} / {@link #OUTCOME_TARGET_KILLED_ECHO}
     */
    public static void reportOutcome(int noteId, String outcome) {
        try {
            if (noteId <= 0 || outcome == null || outcome.isBlank()) return;
            RelayOutbox.get().enqueue("/deathnotes/outcome", buildOutcomePayload(noteId, outcome).toString());
            LOGGER.debug("[DungeonTrain] DeathNote outcome {} for note {} queued to the relay outbox.",
                    outcome, noteId);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] DeathNote outcome failed to build: {}", t.toString());
        }
    }

    /**
     * Report the full encounter journal for a landed curse — the ordered beats, the gear the echo bore
     * and claimed, how long it lasted and how it ended — so the author's story book can narrate what
     * actually happened rather than just naming a carriage. Rides the same write-once
     * {@code /deathnotes/outcome} endpoint as {@link #reportOutcome}.
     *
     * @param noteId    the relay note id the echo was spawned from
     * @param outcome   the ending, or {@code null} when the encounter produced no verdict (the echo was
     *                  left behind, or the target died to something else) — the journal still ships
     * @param encounter the journal object built by {@code CursedEncounterPayload}
     */
    public static void reportEncounter(int noteId, String outcome, JsonObject encounter) {
        try {
            if (noteId <= 0 || encounter == null) return;
            JsonObject body = new JsonObject();
            body.addProperty("id", noteId);
            if (outcome != null && !outcome.isBlank()) body.addProperty("outcome", outcome);
            body.add("encounter", encounter);
            RelayOutbox.get().enqueue("/deathnotes/outcome", body.toString());
            LOGGER.debug("[DungeonTrain] DeathNote encounter for note {} (ending {}) queued to the relay outbox.",
                    noteId, outcome == null ? "none" : outcome);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] DeathNote encounter failed to build: {}", t.toString());
        }
    }

    /**
     * Report that the AUTHOR read note {@code noteId}'s story book — flips the relay's one-way
     * {@code storyRead} latch so that curse's story is never handed out again (one story per curse).
     * Durable + idempotent. No-throw.
     */
    public static void markStoryRead(int noteId) {
        try {
            if (noteId <= 0) return;
            JsonObject body = new JsonObject();
            body.addProperty("id", noteId);
            RelayOutbox.get().enqueue("/deathnotes/story-read", body.toString());
            LOGGER.debug("[DungeonTrain] DeathNote story-read {} queued to the relay outbox.", noteId);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] DeathNote story-read failed to build: {}", t.toString());
        }
    }

    /** The curse ran its course: the author's echo killed the target it was written for. */
    public static final String OUTCOME_ECHO_KILLED_TARGET = "echo_killed_target";
    /** The target fought the echo off — the curse landed but was survived. */
    public static final String OUTCOME_TARGET_KILLED_ECHO = "target_killed_echo";

    /** Pure assembly of the {@code /deathnotes/outcome} body — package-private for unit tests. */
    static JsonObject buildOutcomePayload(int noteId, String outcome) {
        JsonObject body = new JsonObject();
        body.addProperty("id", noteId);
        body.addProperty("outcome", outcome);
        return body;
    }

    /**
     * Pure assembly of the {@code /deathnotes/submit} JSON body — package-private so the shape can be
     * unit-tested without a running server. Matches the relay contract exactly. Null strings emit as "".
     */
    static JsonObject buildPayload(String authorUuid, String authorName, String targetName, String targetUuid,
                                   int deathCarriage, String worldKey, String authorSkinRef, boolean freePlay) {
        JsonObject body = new JsonObject();
        body.addProperty("authorUuid", authorUuid == null ? "" : authorUuid);
        body.addProperty("authorName", authorName == null ? "" : authorName);
        body.addProperty("targetName", targetName == null ? "" : targetName);
        body.addProperty("targetUuid", targetUuid == null ? "" : targetUuid);
        body.addProperty("deathCarriage", deathCarriage);
        body.addProperty("worldKey", worldKey == null ? "" : worldKey);
        body.addProperty("authorSkinRef", authorSkinRef == null ? "" : authorSkinRef);
        body.addProperty("freePlay", freePlay);
        return body;
    }
}
