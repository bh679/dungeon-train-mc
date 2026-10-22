package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.CloseCallPace.Metric;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirements;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which rare single-life advancements a player is "close" to, for the Live Eye close-call alert
 * ({@code event/LiveCloseCallEvents}): the on-air streamer is told someone is a few minutes from one
 * and can hand them the Live Eye.
 *
 * <p>Close = estimated under {@link #CLOSE_MS} away at the player's recent pace ({@link CloseCallPace}),
 * still eligible, not yet earned. {@link #TARGETS} is in rarity order; when several are close at once
 * only the first is reported, and all of them count as alerted for the rest of the life.</p>
 *
 * <p>All three are hidden advancements: nothing here, in the alert or in lang, names them.</p>
 */
public final class CloseCallRules {

    public static final long CLOSE_MS = 20L * 60_000L;

    /**
     * One watched advancement. {@code floor}: the reading must be at least this before an alert,
     * whatever the pace (The Challenge's 750).
     */
    public record Target(ResourceLocation id, Metric metric, long defaultThreshold, long floor) {
        long threshold() {
            return AdvancementRequirements.value(id, defaultThreshold);
        }
    }

    public static final Target THE_CHALLENGE =
        new Target(AllAtOnceAdvancement.THE_CHALLENGE, Metric.CARRIAGES, 1000, 750);
    public static final Target NEARING_INFINITY =
        new Target(dt("dungeon_train/carts_10000"), Metric.CARRIAGES, 10_000, 0);
    public static final Target THE_LONG_RUN =
        new Target(dt("dungeon_train/the_long_run"), Metric.METRES, 250_000, 0);

    /** Rarest first. */
    public static final List<Target> TARGETS = List.of(THE_CHALLENGE, NEARING_INFINITY, THE_LONG_RUN);

    private CloseCallRules() {}

    private static ResourceLocation dt(String path) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path);
    }

    /** Pure: {@code value} of {@code threshold} is past {@code floor}, short of the threshold, and due within {@link #CLOSE_MS}. */
    static boolean isClose(double value, long threshold, long floor, OptionalLong etaMs) {
        return value >= floor && value < threshold && etaMs.isPresent() && etaMs.getAsLong() <= CLOSE_MS;
    }

    /** What one evaluation found: the targets close now (rarest first) and the soonest ETA among them. */
    public record Found(List<Target> close, long etaMs) {
        public static final Found NONE = new Found(List.of(), 0L);

        public boolean any() {
            return !close.isEmpty();
        }
    }

    /**
     * The targets {@code eligible} admits that are close at {@code pace}, skipping {@code alerted}
     * (ids already reported this life). {@code reading} is the player's carriages and metres now.
     */
    public static Found find(CloseCallPace pace, CloseCallPace.Sample reading, Predicate<Target> eligible,
                             Set<String> alerted) {
        List<Target> close = new ArrayList<>();
        long soonest = Long.MAX_VALUE;
        for (Target t : TARGETS) {
            if (alerted.contains(t.id().toString()) || !eligible.test(t)) continue;
            long threshold = t.threshold();
            double value = t.metric() == Metric.CARRIAGES ? reading.carriages() : reading.metres();
            OptionalLong eta = pace.etaMs(t.metric(), threshold);
            if (!isClose(value, threshold, t.floor(), eta)) continue;
            close.add(t);
            soonest = Math.min(soonest, eta.getAsLong());
        }
        return close.isEmpty() ? Found.NONE : new Found(List.copyOf(close), soonest);
    }

    /** Whole minutes to show for {@code etaMs}: at least 1, at most {@link #CLOSE_MS}'s. */
    public static int minutes(long etaMs) {
        long m = (etaMs + 59_999L) / 60_000L;
        return (int) Math.max(1, Math.min(CLOSE_MS / 60_000L, m));
    }
}
