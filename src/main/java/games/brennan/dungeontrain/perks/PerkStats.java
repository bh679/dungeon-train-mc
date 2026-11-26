package games.brennan.dungeontrain.perks;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * The key stats each perk moves, as the menu's details bar shows them: per perk id, a list of stats,
 * each a function of the perk's points. The functions are the effect code's own maths, registered by
 * that code ({@link BuiltPerks}), so a chip can never say something the perk doesn't do. The bar
 * shows each stat at the perk's points and previews a point's value on hover.
 */
public final class PerkStats {

    /** How a number is shown; each has a lang key {@code gui.dungeontrain.perks.stat.unit.<name>}. */
    public enum Unit {
        PLAIN, PERCENT, SECONDS, MINUTES, MULTIPLIER, BLOCKS,
        /** Drawn as heart sprites, two hit points to a heart. The value is in hit points. */
        HEARTS,
        /** Drawn as armour sprites, two armour points to an icon. The value is in armour points. */
        ARMOUR,
        /** Drawn as an emerald, rounded down to whole emeralds. */
        EMERALDS;

        /** Units the bar draws as sprites rather than a lang key. */
        public boolean isIcon() {
            return this == HEARTS || this == ARMOUR || this == EMERALDS;
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A stat's value at some number of points. */
    public sealed interface Value permits Num, Text, Or {}

    /** A number in a unit; {@code signed} shows a leading + on positive values. */
    public record Num(double value, Unit unit, boolean signed) implements Value {}

    /** A value only words can say, like a Strength level. */
    public record Text(Component text) implements Value {}

    /** One of two values by chance, shown "a or b" (Share Hearts' gift on a point that rolls for more). */
    public record Or(Value first, Value second) implements Value {}

    /**
     * One stat: its lang id ({@code gui.dungeontrain.perks.stat.<id>}), its value by points, and the
     * label's arguments by points ({@code labelArgs} may be null: a plain label). A null value hides the
     * stat at those points, so an overflow perk can show "Luck I always" only from 100%.
     */
    public record Stat(String id, IntFunction<Value> at, IntFunction<Object[]> labelArgs, boolean stepped) {
        public Stat(String id, IntFunction<Value> at) {
            this(id, at, null, false);
        }

        public Stat(String id, IntFunction<Value> at, IntFunction<Object[]> labelArgs) {
            this(id, at, labelArgs, false);
        }

        /**
         * For a {@link #stepped} stat: the first points after {@code points}, at most {@link #LOOK_AHEAD}
         * further, where the value differs from its value at {@code points}; -1 when there is none.
         * The menu shows it as "→ 2 at 5 points" when the next point alone changes nothing.
         */
        public int nextChangeAfter(int points) {
            Value now = valueAt(points);
            for (int k = Math.max(0, points) + 1; k <= Math.max(0, points) + LOOK_AHEAD; k++) {
                Value v = valueAt(k);
                if (v != null && !v.equals(now)) return k;
            }
            return -1;
        }

        /** The value at {@code points}, or null when the stat is hidden there. */
        public Value valueAt(int points) {
            return at.apply(Math.max(0, points));
        }

        /** The label's arguments at {@code points}; empty for a plain label. */
        public Object[] labelArgsAt(int points) {
            if (labelArgs == null) return NO_ARGS;
            Object[] args = labelArgs.apply(Math.max(0, points));
            return args == null ? NO_ARGS : args;
        }
    }

    private static final Object[] NO_ARGS = new Object[0];

    /** How far a stepped stat's preview looks for its next change. */
    public static final int LOOK_AHEAD = 100;

    private static final Map<String, List<Stat>> STATS = Collections.synchronizedMap(new LinkedHashMap<>());

    private PerkStats() {}

    /** Add a stat to a perk, after any it already has. Registering the same stat id twice replaces it. */
    public static void register(String perkId, String statId, IntFunction<Value> at) {
        register(perkId, statId, null, at);
    }

    /**
     * As {@link #register(String, String, IntFunction)}, with label arguments by points: the label's lang
     * value takes them as {@code %s} (so "Luck %s chance" reads "Luck I chance" or "Luck II chance").
     */
    public static void register(String perkId, String statId, IntFunction<Object[]> labelArgs, IntFunction<Value> at) {
        add(perkId, new Stat(statId, at, labelArgs, false));
    }

    /**
     * A stat that changes only every few points (Standless Brewing's bottle slots). When the next point
     * leaves it as it is, its preview names the next value and where it comes: "→ 2 at 5 points".
     */
    public static void registerStepped(String perkId, String statId, IntFunction<Value> at) {
        add(perkId, new Stat(statId, at, null, true));
    }

    private static void add(String perkId, Stat stat) {
        String statId = stat.id();
        if (!statId.matches("[a-z0-9_]+")) throw new IllegalArgumentException("bad stat id " + statId);
        synchronized (STATS) {
            List<Stat> list = new ArrayList<>(STATS.getOrDefault(perkId, List.of()));
            list.removeIf(s -> s.id().equals(statId));
            list.add(stat);
            STATS.put(perkId, List.copyOf(list));
        }
    }

    /** A perk's stats; empty when it has none. */
    public static List<Stat> of(String perkId) {
        BuiltPerks.bootstrap();
        return STATS.getOrDefault(perkId, List.of());
    }

    /** Every perk id that has stats. */
    static java.util.Set<String> perkIds() {
        BuiltPerks.bootstrap();
        synchronized (STATS) {
            return java.util.Set.copyOf(STATS.keySet());
        }
    }

    public static Value num(double value, Unit unit) {
        return new Num(value, unit, false);
    }

    public static Value plus(double value, Unit unit) {
        return new Num(value, unit, true);
    }

    /** A chip that is its label alone, like "Luck I always". */
    public static Value labelOnly() {
        return LABEL_ONLY;
    }

    private static final Value LABEL_ONLY = new Text(Component.empty());
}
