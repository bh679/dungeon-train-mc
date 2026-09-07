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
 * Watches a player for a moment after a portal swap and reports, in one line, whether anything
 * went on moving them.
 *
 * <p>A dimensional carriage's twin is stamped into the static world, so a player who arrives in one
 * and stands still should stay exactly where they landed. In the 0.817.1 dev session they did not:
 * standing in the twin and turning on the spot walked them down the corridor. The swap log alone
 * could not say why, because it only sees the two instants either side of a teleport — what was
 * needed was the ticks in between, and whether Sable still had them attached to the carriage they
 * had just left. Sampled that way, the answer was unambiguous: 0.100, 0.069, 0.048, 0.033 blocks a
 * tick — the train's speed decaying at Sable's drag constant — with no sub-level carrying them. See
 * {@code ship.sable.SableEntityCarry} for what that turned out to be and what now sheds it.</p>
 *
 * <p><b>One line per arrival, not one per tick.</b> The per-tick form that found the bug logged a
 * few hundred lines a session, most of them a player simply walking. What is kept is the summary
 * that would have caught it just as well: how far they moved over the window, the biggest single
 * tick, and who Sable said was carrying them on the first two ticks — the second of which is where
 * a carry that outlived the swap would show. A walker reads as a few blocks with no carrier; a
 * player standing still should read as nothing at all.</p>
 *
 * <p>The window ends early when the player leaves the twin again, so nothing on the train side —
 * where being carried at track speed is the whole point — is counted against the swap.</p>
 *
 * <p>Costs nothing when nobody has swapped: the map is empty and the sampler returns on its first
 * line.</p>
 */
public final class PortalSwapDrift {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long after a swap to keep watching — two seconds, well past the swap cooldown. */
    private static final int WATCH_TICKS = 40;

    /** Movement below this in a tick is float noise, not a carry, and is not counted. */
    private static final double COUNT_THRESHOLD = 0.001;

    private static final class Watch {
        final int carriageIndex;
        Vec3 last;
        int elapsed;
        int movedTicks;
        double total;
        double maxTick;
        String carrierT1 = "?";
        String carrierT2 = "?";

        Watch(Vec3 start, int carriageIndex) {
            this.last = start;
            this.carriageIndex = carriageIndex;
        }
    }

    private static final Map<UUID, Watch> WATCHING = new HashMap<>();

    private PortalSwapDrift() {}

    /** Start watching {@code player}, who has just been put down in a twin corridor. */
    public static void noteArrival(ServerPlayer player, int carriageIndex) {
        WATCHING.put(player.getUUID(), new Watch(player.position(), carriageIndex));
    }

    /** {@code player} has gone back to the train: close their window now and report it. */
    public static void noteDeparture(ServerPlayer player) {
        Watch watch = WATCHING.remove(player.getUUID());
        if (watch != null) report(player, watch, "left for the train");
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
            watch.elapsed++;
            Vec3 now = player.position();
            double moved = now.subtract(watch.last).length();
            watch.last = now;
            if (moved > COUNT_THRESHOLD) {
                watch.movedTicks++;
                watch.total += moved;
                watch.maxTick = Math.max(watch.maxTick, moved);
            }
            if (watch.elapsed == 1) watch.carrierT1 = carrierName.apply(player);
            if (watch.elapsed == 2) watch.carrierT2 = carrierName.apply(player);

            if (watch.elapsed >= WATCH_TICKS) {
                it.remove();
                report(player, watch, "window over");
            }
        }
    }

    /** Forget everyone — a world unload leaves no player worth watching. */
    public static void clear() {
        WATCHING.clear();
    }

    private static void report(ServerPlayer player, Watch watch, String why) {
        LOGGER.info("[DungeonTrain] Portal swap drift: player={} carriage={} over {} ticks moved {} blocks in {} ticks (max {}/tick) carriedBy t+1={} t+2={} — {}",
            player.getName().getString(), watch.carriageIndex, watch.elapsed,
            fmt(watch.total), watch.movedTicks, fmt(watch.maxTick),
            watch.carrierT1, watch.carrierT2, why);
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
