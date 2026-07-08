package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.server.level.ServerPlayer;

/**
 * Central authorisation for the community shared-books feature — one place both halves consult so the
 * config masters and consent rules can't drift between the sign-interception mixin, the reporter, the
 * pool refresh, and the loot hook.
 *
 * <ul>
 *   <li>{@link #canContribute(ServerPlayer)} — may this player's signed book be uploaded? Requires the
 *       server-operator master ({@link DungeonTrainConfig#isShareBooksEnabled()}) <b>and</b> the
 *       player's own client to have granted network consent ({@link NetworkConsentMirror#isGranted}).
 *       Contribution sends the player's authored text off-machine, so it is per-player, fail-closed.</li>
 *   <li>{@link #canDiscover()} — may chest books be drawn from the shared pool? Governed only by the
 *       server-operator master ({@link DungeonTrainConfig#isDiscoverSharedBooksEnabled()}). Discovery
 *       is server-wide (found books are already public/approved), so there is no per-player consent —
 *       an operator enabling it opts the whole world in.</li>
 * </ul>
 */
public final class SharedBookGate {

    private SharedBookGate() {}

    /**
     * True when {@code player} may have their signed book uploaded to the relay: the feature is
     * enabled server-side AND this player's client has granted network consent. Fail-closed on a
     * {@code null} player or a missing consent sync.
     */
    public static boolean canContribute(ServerPlayer player) {
        if (player == null) return false;
        return DungeonTrainConfig.isShareBooksEnabled() && NetworkConsentMirror.isGranted(player);
    }

    /** True when the server has opted this world into shared-book chest discovery. */
    public static boolean canDiscover() {
        return DungeonTrainConfig.isDiscoverSharedBooksEnabled();
    }
}
