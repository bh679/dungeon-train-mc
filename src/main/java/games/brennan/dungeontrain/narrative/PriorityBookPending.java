package games.brennan.dungeontrain.narrative;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player "use a Priority Book on the book I am signing right now" — the sign page's
 * toggle, delivered just ahead of vanilla's own edit-book packet by
 * {@link games.brennan.dungeontrain.net.PriorityBookIntentPacket}.
 *
 * <p>Same shape and rules as {@link EditorBookAuthorPending}: vanilla's sign packet has no room for
 * the choice, so it travels separately and waits here. <b>Consume-once</b>: the sign intercept
 * {@link #take}s it at HEAD on every sign, whichever branch it then goes down, so a choice made on one
 * sign screen can never spend a credit on a later book. Only the community shared-book branch acts on
 * it, and the relay is what actually charges (or declines to charge) the player's credits.</p>
 */
public final class PriorityBookPending {

    private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();

    private PriorityBookPending() {}

    /** Mark {@code player}'s next sign as a Priority Book. */
    public static void put(UUID player) {
        if (player != null) PENDING.add(player);
    }

    /** Remove and return whether {@code player}'s next sign was marked. */
    public static boolean take(UUID player) {
        return player != null && PENDING.remove(player);
    }

    /** Drop any pending choice — on logout, so a choice never outlives the session that made it. */
    public static void clear(UUID player) {
        if (player != null) PENDING.remove(player);
    }
}
