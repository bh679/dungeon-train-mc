package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Tells the relay where a Developer message got to on the player's side, so the dashboard's Chat
 * page can say whether a reply is still waiting on the title screen, waiting behind the in-game
 * consent prompt, or already read in chat.
 *
 * <p>WHY THE RELAY CANNOT WORK THIS OUT ITSELF. It knows its own inbox cursor (the title-screen
 * panel) and it can see the 👀/✅ reaction receipts, but the consent handshake in
 * {@link games.brennan.dungeontrain.event.DevMessageConsent} happens entirely in-game. Crucially the
 * case that matters most — consent already valid, so the message goes straight to chat — produces no
 * Discord traffic at all, which is why the existing {@link DevMessageReport} notices (posted only
 * around the <em>prompt</em>) cannot stand in for this.</p>
 *
 * <p>Mirrors {@link RunSummaryReporter}: same durable {@link RelayOutbox} hand-off (persisted,
 * at-least-once on the next flush), the same {@link DungeonTrainConfig#isWorldInfoToRelay()} gate,
 * and the same never-throw contract — a reporting failure must never disrupt the consent flow that
 * called it, which is the thing actually delivering the player their message.</p>
 *
 * <p>State is per PLAYER, not per message: the consent flow queues pending messages by player and
 * no relay message id is threaded through it. The dashboard words its status accordingly.</p>
 */
public final class DevMessageStateReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The in-game consent prompt was shown; the message is held until they type {@code @Dev}. */
    public static final String CONSENT_REQUESTED = "consent_requested";
    /** They accepted; the held messages are on their way to chat. */
    public static final String CONSENT_ACCEPTED = "consent_accepted";
    /** Consent was already valid, so the message went straight to their chat — no prompt involved. */
    public static final String DELIVERED_IN_GAME = "delivered_in_game";

    private DevMessageStateReporter() {}

    /** Best-effort: report that {@code player}'s dev-message delivery reached {@code state}. */
    public static void report(ServerPlayer player, String state) {
        try {
            if (player == null || state == null || !DungeonTrainConfig.isWorldInfoToRelay()) {
                return;
            }
            String uuid = player.getUUID().toString().replace("-", "");
            RelayOutbox.get().enqueue("/telemetry/dev-message-state", buildPayload(uuid, state).toString());
            LOGGER.debug("[DungeonTrain] dev-message state '{}' for {} queued to the relay outbox.", state, uuid);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] dev-message state report failed: {}", t.toString());
        }
    }

    /**
     * Pure payload assembly over plain data — package-private so the shape can be unit-tested without
     * bootstrapping the game. The timestamp is stamped here rather than on arrival because the relay
     * ignores a report older than the state it already holds, and an outbox flush can be minutes
     * late: without a send-time stamp a retried "consent requested" could overwrite the "delivered"
     * that followed it.
     */
    static JsonObject buildPayload(String uuid, String state) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("state", state);
        body.addProperty("ts", System.currentTimeMillis());
        return body;
    }
}
