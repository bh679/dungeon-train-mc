package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a book loot entry is enchanted with ({@link ContainerContentsEntry#enchants()}).
 *
 * <p>{@code exact} are stored enchantments every roll gets, id to level: a Mending book is
 * {@code {minecraft:mending: 1}}. {@code randomMin}..{@code randomMax} more are rolled per book, each
 * different, at its max level and never a curse ({@link ContainerContentsEnchants#rollRandom}).
 * {@code randomMax} 0 means none.</p>
 */
public record BookEnchants(Map<ResourceLocation, Integer> exact, int randomMin, int randomMax) {

    /** Most random enchantments one book can roll. */
    public static final int MAX_RANDOM = 16;
    /** Highest stored level an exact enchantment keeps; vanilla's own cap on the component. */
    public static final int MAX_LEVEL = 255;

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
    }

    public static BookEnchants exact(Map<ResourceLocation, Integer> exact) {
        return new BookEnchants(exact, 0, 0);
    }

    public static BookEnchants random(int min, int max) {
        return new BookEnchants(Map.of(), min, max);
    }

    public boolean isEmpty() {
        return exact.isEmpty() && randomMax == 0;
    }

    public boolean hasRandom() {
        return randomMax > 0;
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
