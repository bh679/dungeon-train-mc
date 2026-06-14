package games.brennan.dungeontrain.discord;

import games.brennan.dungeontrain.config.DungeonTrainConfig;

import java.util.UUID;

/**
 * Decides whether a joining player should carry the developer ping-marker on their Discord join
 * message: the marker fires on the <b>first new world the player starts/joins after their
 * first-ever death</b>, at most once per player, ever.
 *
 * <p>The actual {@code <@mention>} is NOT built here — the brennan.games relay swaps the
 * configured marker token for the real mention (and sets {@code allowed_mentions} so it pings),
 * keeping the developer's Discord id off the jar. This service only decides <i>when</i> to emit
 * the token.</p>
 *
 * <p>"New world" = a world created <i>after</i> the player's first death, detected by comparing
 * the per-world creation time (cached here at server start from {@code DungeonTrainWorldData}, so
 * the join path never touches per-world SavedData) against the cross-world first-death time
 * ({@link GlobalDeathPingStore}). Legacy worlds created before this feature have a creation time
 * of {@code 0} and therefore never qualify.</p>
 *
 * <p>When the configured token is blank the feature is <b>dormant</b>: nothing is emitted and the
 * once-only flag is left unconsumed, so players stay eligible until the token is set.</p>
 */
public final class DevPingService {

    /**
     * Wall-clock creation time (millis) of the running server's overworld, cached at server start
     * (from {@code TrainBootstrapEvents.onServerStarted}, a both-dist hook) so
     * {@link #relayMarkerIfQualifies} never reads per-world SavedData off the server thread.
     * {@code 0} when no server is running or the world predates the feature. Re-set on every
     * server start — which always precedes any join — so a previous world's value never leaks.
     */
    private static volatile long currentWorldCreatedAtMillis = 0L;

    private DevPingService() {}

    /** Cache the active world's creation time. Call once from the both-dist server-started hook. */
    public static void setCurrentWorldCreatedAt(long millis) {
        currentWorldCreatedAtMillis = millis;
    }

    /**
     * The relay ping-marker token for this player when they qualify for the one-time developer
     * ping, else {@code ""}. A blank configured token keeps the feature dormant (emits nothing and
     * does NOT consume eligibility). When the token is set and the player qualifies, the once-only
     * flag is consumed atomically so the marker is emitted at most once, ever.
     */
    public static String relayMarkerIfQualifies(UUID playerId) {
        String token = DungeonTrainConfig.getDeveloperPingRelayToken();
        if (token.isBlank()) {
            return ""; // dormant — leave eligibility intact until a token is configured
        }
        long firstDeathAt = GlobalDeathPingStore.firstDeathAt(playerId);
        if (firstDeathAt == 0L) {
            return ""; // player has never died
        }
        if (currentWorldCreatedAtMillis <= firstDeathAt) {
            return ""; // same / older world than where they first died — not a NEW world
        }
        // Qualifies. Consume the once-ever flag atomically; emit the token only if we won the race.
        return GlobalDeathPingStore.markDevPingSentIfUnset(playerId) ? token : "";
    }
}
