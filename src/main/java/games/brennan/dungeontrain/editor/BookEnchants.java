package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a book loot entry is enchanted with ({@link ContainerContentsEntry#enchants()}).
 *
 * <p>{@code exact} are stored enchantments every roll gets, id to level: a Mending book is
 * {@code {minecraft:mending: 1}}. {@code randomMin}..{@code randomMax} more are rolled per book, each
 * different ({@link ContainerContentsEnchants#rollRandom}). {@code randomMax} 0 means none.</p>
 *
 * <p>{@code levelMin}..{@code levelMax} is the level each random one rolls at, capped at that
 * enchantment's own top level; 0 at either end means "its top level", so 0..0 (the default) is
 * always the top level and 3..0 is "3 up to the top".</p>
 *
 * <p>{@code list} is the builder's on/off decision per enchantment the random roll could pick. An
 * enchantment missing from a saved list is undecided: it never rolls, and the menu asks about it.
 * {@code null} (pools saved before the list existed) keeps the old rule: everything in
 * {@code #minecraft:on_random_loot} except curses and Mending.</p>
 */
public record BookEnchants(Map<ResourceLocation, Integer> exact, int randomMin, int randomMax,
                           int levelMin, int levelMax, @Nullable Map<ResourceLocation, Boolean> list) {

    /** Most random enchantments one book can roll. */
    public static final int MAX_RANDOM = 16;
    /** Highest stored level an exact enchantment keeps; vanilla's own cap on the component. */
    public static final int MAX_LEVEL = 255;
    /** Highest level the menu's Level range offers; enchantments cap at their own top level anyway. */
    public static final int MAX_LEVEL_SETTING = 10;
    /** The Level range's "top level" end. */
    public static final int LEVEL_TOP = 0;

    public BookEnchants {
        TreeMap<ResourceLocation, Integer> sorted = new TreeMap<>();
        if (exact != null) {
            exact.forEach((id, level) -> {
                if (id != null && level != null && level > 0) sorted.put(id, Math.min(MAX_LEVEL, level));
            });
        }
        exact = Collections.unmodifiableMap(sorted);
        randomMax = Math.max(0, Math.min(MAX_RANDOM, randomMax));
        randomMin = Math.max(0, Math.min(randomMax, randomMin));
        levelMax = Math.max(LEVEL_TOP, Math.min(MAX_LEVEL_SETTING, levelMax));
        levelMin = Math.max(LEVEL_TOP, Math.min(MAX_LEVEL_SETTING, levelMin));
        if (levelMax != LEVEL_TOP && levelMin > levelMax) levelMin = levelMax;
        if (list != null) {
            TreeMap<ResourceLocation, Boolean> decided = new TreeMap<>();
            list.forEach((id, on) -> {
                if (id != null && on != null) decided.put(id, on);
            });
            list = Collections.unmodifiableMap(decided);
        }
    }

    public BookEnchants(Map<ResourceLocation, Integer> exact, int randomMin, int randomMax) {
        this(exact, randomMin, randomMax, LEVEL_TOP, LEVEL_TOP, null);
    }

    public static BookEnchants exact(Map<ResourceLocation, Integer> exact) {
        return new BookEnchants(exact, 0, 0);
    }

    public static BookEnchants random(int min, int max) {
        return new BookEnchants(Map.of(), min, max);
    }

    /** These enchantments with the random range set to {@code min}..{@code max} (max 0: none). */
    public BookEnchants withRandom(int min, int max) {
        return new BookEnchants(exact, min, max, levelMin, levelMax, list);
    }

    /** These enchantments with the random ones rolling at {@code min}..{@code max} ({@link #LEVEL_TOP}: top level). */
    public BookEnchants withLevels(int min, int max) {
        return new BookEnchants(exact, randomMin, randomMax, min, max, list);
    }

    public BookEnchants withList(@Nullable Map<ResourceLocation, Boolean> v) {
        return new BookEnchants(exact, randomMin, randomMax, levelMin, levelMax, v);
    }

    public boolean isEmpty() {
        return exact.isEmpty() && randomMax == 0;
    }

    public boolean hasRandom() {
        return randomMax > 0;
    }

    /** True when the random ones always roll at their top level: the default. */
    public boolean isTopLevelOnly() {
        return levelMin == LEVEL_TOP && levelMax == LEVEL_TOP;
    }

    /** The level a random pick of an enchantment whose top level is {@code top} may roll, lowest first. */
    public int levelLow(int top) {
        int hi = levelHigh(top);
        return levelMin == LEVEL_TOP ? hi : Math.min(levelMin, hi);
    }

    public int levelHigh(int top) {
        return levelMax == LEVEL_TOP ? top : Math.min(levelMax, top);
    }

    /** Short level label: "max", "3-max", "2-4", "3". */
    public String levelLabel() {
        return levelLabel(levelMin, levelMax);
    }

    /** {@link #levelLabel()} for a level range on its own, as the menu receives it. */
    public static String levelLabel(int levelMin, int levelMax) {
        String hi = levelMax == LEVEL_TOP ? "max" : String.valueOf(levelMax);
        if (levelMin == LEVEL_TOP || String.valueOf(levelMin).equals(hi)) return hi;
        return levelMin + "-" + hi;
    }

    /** Short menu label: "mending 1", "1-7 random", or both. */
    public String label() {
        StringBuilder sb = new StringBuilder();
        exact.forEach((id, level) -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(id.getPath()).append(' ').append(level);
        });
        if (hasRandom()) {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append(randomMin == randomMax ? String.valueOf(randomMax) : randomMin + "-" + randomMax)
                .append(" random");
        }
        return sb.toString();
    }
}
