package games.brennan.dungeontrain.portal;

import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Which entities have been standing in a portal room lately, so vanilla does not reap them.
 *
 * <p>The portal world is at the bottom of the world with the train rolling away overhead. To
 * vanilla that is simply a mob a long way from any player, and the despawn rule takes it — a
 * villager walked through a portal would be gone by the time its player came back for it. Nothing
 * about the room tells vanilla otherwise, so this does.</p>
 *
 * <p><b>An id registry rather than a position test.</b> The per-tick pair loop already walks the
 * structure to decide whether it is occupied, so noting what it finds there costs nothing, and
 * {@code PortalDespawnEvents} then answers with one map lookup. Asking "is this mob inside any
 * portal room?" at despawn time would instead mean testing every live room's box against every mob
 * in the world, on a path that runs for every mob in the game.</p>
 *
 * <p><b>Why a window rather than a flag.</b> Protection lapses on its own, so nothing has to notice
 * an entity leaving, and an entity that stops being in a room goes back to ordinary rules without
 * anything being written onto it. The window is long enough to cover stepping back onto the train
 * and returning, which would otherwise empty the room behind you.</p>
 */
public final class PortalOccupants {

    /**
     * How long a sighting keeps an entity safe, in ticks.
     *
     * <p>Twenty seconds: comfortably longer than a trip out to the train and back, short enough that
     * the registry drains on its own once a room is genuinely abandoned. It does not have to cover
     * long absences — anything that walked in through the portal is also marked persistent, which
     * outlasts any window.</p>
     */
    private static final long GRACE_TICKS = 400;

    /** Entity id → game time after which the sighting no longer counts. */
    private static final Map<Integer, Long> PROTECTED = new HashMap<>();

    /** Prune when the registry has grown past anything a set of portal rooms could hold. */
    private static final int PRUNE_THRESHOLD = 256;

    private PortalOccupants() {}

    /** Note that this entity is in a portal room right now. */
    public static void protect(Entity entity, long gameTime) {
        PROTECTED.put(entity.getId(), gameTime + GRACE_TICKS);
        if (PROTECTED.size() > PRUNE_THRESHOLD) prune(gameTime);
    }

    /** True if this entity was in a portal room recently enough to be spared. */
    public static boolean isProtected(int entityId, long gameTime) {
        Long until = PROTECTED.get(entityId);
        if (until == null) return false;
        if (gameTime >= until) {
            PROTECTED.remove(entityId);
            return false;
        }
        return true;
    }

    public static boolean isEmpty() {
        return PROTECTED.isEmpty();
    }

    /** Forget everything, for a world unload or a server stop. */
    public static void clear() {
        PROTECTED.clear();
    }

    private static void prune(long gameTime) {
        Iterator<Map.Entry<Integer, Long>> it = PROTECTED.entrySet().iterator();
        while (it.hasNext()) {
            if (gameTime >= it.next().getValue()) it.remove();
        }
    }
}
