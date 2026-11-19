package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.CloseCallPace.Metric;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirements;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which rare single-life advancements a player is "close" to, for the Live Eye close-call alert
 * ({@code event/LiveCloseCallEvents}): the on-air streamer is told someone is a few minutes from one
 * and can hand them the Live Eye.
 *
 * <p>Close = estimated within a {@link Stage} of it at the player's recent pace ({@link CloseCallPace}),
 * still eligible, not yet earned. Each advancement alerts twice a life: once at {@link Stage#EARLY}
 * (20 minutes) and again at {@link Stage#FINAL} (5 minutes); reaching the final stage first skips the
 * early one. {@link #TARGETS} is in rarity order; when several are close at once only one alert goes
 * out, and every one of them counts as alerted at its stage.</p>
 *
 * <p>All three are hidden advancements: nothing here, in the alert or in lang, names them.</p>
 */
public final class CloseCallRules {

    /** The two alerts per advancement per life, loosest first. */
    public enum Stage {
        EARLY(20L * 60_000L), FINAL(5L * 60_000L);

        public final long withinMs;

        Stage(long withinMs) {
            this.withinMs = withinMs;
        }

        /** The tightest stage {@code etaMs} is within, or null when it is further than every stage. */
        static Stage of(long etaMs) {
            Stage hit = null;
            for (Stage s : values()) if (etaMs <= s.withinMs) hit = s;
            return hit;
        }
    }

    /** One watched advancement. */
    public record Target(ResourceLocation id, Metric metric, long defaultThreshold) {
        long threshold() {
            return AdvancementRequirements.value(id, defaultThreshold);
        }

        /** The alerted-this-life key for this target at {@code stage}. */
        public String key(Stage stage) {
            return id + "@" + stage.name().toLowerCase(Locale.ROOT);
        }
    }

    public static final Target THE_CHALLENGE =
        new Target(AllAtOnceAdvancement.THE_CHALLENGE, Metric.CARRIAGES, 1000);
    public static final Target NEARING_INFINITY =
        new Target(dt("dungeon_train/carts_10000"), Metric.CARRIAGES, 10_000);
    public static final Target THE_LONG_RUN =
        new Target(dt("dungeon_train/the_long_run"), Metric.METRES, 250_000);

    /** Rarest first. */
    public static final List<Target> TARGETS = List.of(THE_CHALLENGE, NEARING_INFINITY, THE_LONG_RUN);

    private CloseCallRules() {}

    private static ResourceLocation dt(String path) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path);
    }

    /** Pure: the stage {@code value} of {@code threshold} is at, or null (reached, no estimate, or too far). */
    static Stage stageOf(double value, long threshold, OptionalLong etaMs) {
        if (value >= threshold || etaMs.isEmpty()) return null;
        return Stage.of(etaMs.getAsLong());
    }

    /**
     * What one evaluation found: the stage to report (the tightest any target newly reached), the targets
     * that newly reached a stage (rarest first), the keys to mark alerted, and the soonest ETA among them.
     */
    public record Found(Stage stage, List<Target> close, List<String> keys, long etaMs) {
        public static final Found NONE = new Found(null, List.of(), List.of(), 0L);

        public boolean any() {
            return !close.isEmpty();
        }
    }

    /**
     * The targets {@code eligible} admits that newly reached a stage at {@code pace}, skipping stages in
     * {@code alerted} (keys already reported this life). {@code reading} is the player's carriages and metres
     * now. A target at the final stage marks the early one too, so it never alerts after it.
     */
    public static Found find(CloseCallPace pace, CloseCallPace.Sample reading, Predicate<Target> eligible,
                             Set<String> alerted) {
        List<Target> close = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        Stage reported = null;
        long soonest = Long.MAX_VALUE;
        for (Target t : TARGETS) {
            if (!eligible.test(t)) continue;
            long threshold = t.threshold();
            double value = t.metric() == Metric.CARRIAGES ? reading.carriages() : reading.metres();
            OptionalLong eta = pace.etaMs(t.metric(), threshold);
            Stage stage = stageOf(value, threshold, eta);
            if (stage == null || alerted.contains(t.key(stage))) continue;
            close.add(t);
            for (Stage s : Stage.values()) {
                if (s.ordinal() <= stage.ordinal() && !alerted.contains(t.key(s))) keys.add(t.key(s));
            }
            if (reported == null || stage.ordinal() > reported.ordinal()) reported = stage;
            soonest = Math.min(soonest, eta.getAsLong());
        }
        return close.isEmpty() ? Found.NONE : new Found(reported, List.copyOf(close), List.copyOf(keys), soonest);
    }

    /** Whole minutes to send for {@code etaMs}: at least 1, at most the early stage's. */
    public static int minutes(long etaMs) {
        long m = (etaMs + 59_999L) / 60_000L;
        return (int) Math.max(1, Math.min(Stage.EARLY.withinMs / 60_000L, m));
    }
}
