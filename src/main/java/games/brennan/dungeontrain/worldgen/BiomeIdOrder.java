package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A stable order for biome sets that would otherwise iterate in a per-boot order.
 *
 * <p>{@code Set.copyOf}, {@code HashSet} and anything keyed on a {@code ResourceKey} (identity-hashed) iterate
 * differently from one JVM to the next. Where that order feeds world generation — which biome a weighted roll
 * lands on, or the feature list whose indexes seed every feature — the world comes out different each boot.
 * Sorting by biome id pins it.</p>
 */
public final class BiomeIdOrder {

    /** Biome holders by id; unbound holders (no key) sort first, in a fixed but arbitrary place. */
    public static final Comparator<Holder<Biome>> BY_ID =
        Comparator.comparing(h -> h.unwrapKey().map(k -> k.location().toString()).orElse(""));

    private BiomeIdOrder() {}

    /** An unmodifiable copy of {@code biomes} that iterates in biome-id order. */
    public static Set<Holder<Biome>> sortedCopy(Collection<? extends Holder<Biome>> biomes) {
        LinkedHashSet<Holder<Biome>> sorted = new LinkedHashSet<>();
        biomes.stream().map(h -> (Holder<Biome>) h).sorted(BY_ID).forEach(sorted::add);
        return Collections.unmodifiableSet(sorted);
    }
}
