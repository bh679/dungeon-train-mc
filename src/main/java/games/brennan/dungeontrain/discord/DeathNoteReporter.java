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
     */
    public static void submit(UUID authorId, String authorName, String targetName, String targetUuid,
                              int deathCarriage, String worldKey, String authorSkinRef) {
        try {
            if (authorId == null) return;
            String uuid = authorId.toString().replace("-", "");
            JsonObject payload = buildPayload(uuid, authorName, targetName, targetUuid,
                    deathCarriage, worldKey, authorSkinRef);
            RelayOutbox.get().enqueue("/deathnotes/submit", payload.toString());
            LOGGER.debug("[DungeonTrain] DeathNote submit (target {}, carriage {}) queued to the relay outbox.",
                    targetName, deathCarriage);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] DeathNote submit failed to build: {}", t.toString());
        }
    }

    /**
     * Pure assembly of the {@code /deathnotes/submit} JSON body — package-private so the shape can be
     * unit-tested without a running server. Matches the relay contract exactly. Null strings emit as "".
     */
    static JsonObject buildPayload(String authorUuid, String authorName, String targetName, String targetUuid,
                                   int deathCarriage, String worldKey, String authorSkinRef) {
        JsonObject body = new JsonObject();
        body.addProperty("authorUuid", authorUuid == null ? "" : authorUuid);
        body.addProperty("authorName", authorName == null ? "" : authorName);
        body.addProperty("targetName", targetName == null ? "" : targetName);
        body.addProperty("targetUuid", targetUuid == null ? "" : targetUuid);
        body.addProperty("deathCarriage", deathCarriage);
        body.addProperty("worldKey", worldKey == null ? "" : worldKey);
        body.addProperty("authorSkinRef", authorSkinRef == null ? "" : authorSkinRef);
        return body;
    }
}
