package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Watches a player for a moment after a portal swap and reports whether anything is still moving
 * them.
 *
 * <p>A dimensional carriage's twin is stamped into the static world, so a player who arrives in one
 * and stands still should stay exactly where they landed. In the 0.817.1 dev session they did not:
 * standing in the twin and turning on the spot walked them down the corridor. The swap log alone
 * could not say why, because it only sees the two instants either side of a teleport — what was
 * needed was the ticks in between, and whether Sable still had them attached to the carriage they
 * had just left.</p>
 *
 * <p>So this samples the arriving player once a tick for a short window and logs any tick they moved
 * without asking to, alongside the sub-level Sable thinks is carrying them. One line naming a live
 * sub-level in the twin is the whole diagnosis; a run of lines naming none says something else is
 * moving them and the search goes elsewhere.</p>
 *
 * <p>Costs nothing when nobody has swapped: the map is empty and the sampler returns on its first
 * line.</p>
 */
public final class PortalSwapDrift {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long after a swap to keep watching — two seconds, well past the swap cooldown. */
    private static final int WATCH_TICKS = 40;

    /** Movement below this in a tick is float noise, not a carry, and is not worth a line. */
    private static final double REPORT_THRESHOLD = 0.001;

    private record Watch(Vec3 last, int remaining, int carriageIndex) {}

    private static final Map<UUID, Watch> WATCHING = new HashMap<>();

    private PortalSwapDrift() {}

    /** Start watching {@code player}, who has just been put down in a twin corridor. */
    public static void noteArrival(ServerPlayer player, int carriageIndex) {
        WATCHING.put(player.getUUID(),
            new Watch(player.position(), WATCH_TICKS, carriageIndex));
    }

    /**
     * Sample every watched player once. {@code carrierName} answers "what does Sable think is
     * carrying this player" — passed in rather than called directly so this class stays free of
     * Sable types, the same way the rest of {@code portal} does.
     */
    public static void tick(Iterable<ServerPlayer> players,
                            Function<ServerPlayer, String> carrierName) {
        if (WATCHING.isEmpty()) return;

        Map<UUID, ServerPlayer> live = new HashMap<>();
        for (ServerPlayer player : players) live.put(player.getUUID(), player);

        for (Iterator<Map.Entry<UUID, Watch>> it = WATCHING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Watch> entry = it.next();
            ServerPlayer player = live.get(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }

            Watch watch = entry.getValue();
            Vec3 now = player.position();
            Vec3 moved = now.subtract(watch.last());
            if (moved.length() > REPORT_THRESHOLD) {
                LOGGER.info("[DungeonTrain] Portal swap drift: player={} carriage={} t+{} moved ({}, {}, {}) |{}| known=({}, {}, {}) carriedBy={}",
                    player.getName().getString(), watch.carriageIndex(),
                    WATCH_TICKS - watch.remaining() + 1,
                    fmt(moved.x), fmt(moved.y), fmt(moved.z), fmt(moved.length()),
                    fmt(player.getKnownMovement().x), fmt(player.getKnownMovement().y),
                    fmt(player.getKnownMovement().z),
                    carrierName.apply(player));
            }

            if (watch.remaining() <= 1) {
                it.remove();
            } else {
                entry.setValue(new Watch(now, watch.remaining() - 1, watch.carriageIndex()));
            }
        }
    }

    /** Forget everyone — a world unload leaves no player worth watching. */
    public static void clear() {
        WATCHING.clear();
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
