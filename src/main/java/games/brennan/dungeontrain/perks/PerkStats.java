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
    public sealed interface Value permits Num, Text {}

    /** A number in a unit; {@code signed} shows a leading + on positive values. */
    public record Num(double value, Unit unit, boolean signed) implements Value {}

    /** A value only words can say, like a Strength level. */
    public record Text(Component text) implements Value {}

    /** One stat: its lang id ({@code gui.dungeontrain.perks.stat.<id>}) and its value by points. */
    public record Stat(String id, IntFunction<Value> at) {
        public Value valueAt(int points) {
            return at.apply(Math.max(0, points));
        }
    }

    private static final Map<String, List<Stat>> STATS = Collections.synchronizedMap(new LinkedHashMap<>());

    private PerkStats() {}

    /** Add a stat to a perk, after any it already has. Registering the same stat id twice replaces it. */
    public static void register(String perkId, String statId, IntFunction<Value> at) {
        if (!statId.matches("[a-z0-9_]+")) throw new IllegalArgumentException("bad stat id " + statId);
        synchronized (STATS) {
            List<Stat> list = new ArrayList<>(STATS.getOrDefault(perkId, List.of()));
            list.removeIf(s -> s.id().equals(statId));
            list.add(new Stat(statId, at));
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
}
