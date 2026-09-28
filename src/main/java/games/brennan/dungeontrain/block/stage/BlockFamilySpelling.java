package games.brennan.dungeontrain.block.stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * How vanilla spells a block's stairs / slab / wall sibling. Shared by the palette baker (stairs
 * and slab slots of the solid slots) and {@link StageStoneFamily} (shapes of each stone kind):
 * {@code X → X_stairs}, with the {@code _bricks → _brick_stairs}, {@code _tiles → _tile_stairs},
 * {@code _block → _stairs}, {@code _planks → _stairs} spellings and a few aliases for bases whose
 * family stem differs ({@code deepslate → cobbled_deepslate}, {@code copper_block → cut_copper}).
 */
public final class BlockFamilySpelling {

    private static final String NS = "minecraft:";

    /** Bases whose stairs/slab/wall family carries a different stem. */
    private static final Map<String, String> FAMILY_ALIAS = Map.ofEntries(
        Map.entry(NS + "deepslate", NS + "cobbled_deepslate"),
        Map.entry(NS + "end_stone", NS + "end_stone_bricks"),
        Map.entry(NS + "mud", NS + "mud_bricks"),
        Map.entry(NS + "packed_mud", NS + "mud_bricks"),
        Map.entry(NS + "netherrack", NS + "nether_bricks"),
        Map.entry(NS + "copper_block", NS + "cut_copper"),
        Map.entry(NS + "exposed_copper", NS + "exposed_cut_copper"),
        Map.entry(NS + "weathered_copper", NS + "weathered_cut_copper"),
        Map.entry(NS + "oxidized_copper", NS + "oxidized_cut_copper"),
        Map.entry(NS + "waxed_copper_block", NS + "waxed_cut_copper"),
        Map.entry(NS + "waxed_exposed_copper", NS + "waxed_exposed_cut_copper"),
        Map.entry(NS + "waxed_weathered_copper", NS + "waxed_weathered_cut_copper"),
        Map.entry(NS + "waxed_oxidized_copper", NS + "waxed_oxidized_cut_copper"));

    private BlockFamilySpelling() {}

    /**
     * {@code base}'s {@code suffix} sibling ({@code "_stairs"}, {@code "_slab"}, {@code "_wall"}),
     * or null when no spelling {@code exists}. {@code exists} is injected so the rule set is
     * unit-testable without a registry.
     */
    public static String variantOf(String base, String suffix, Predicate<String> exists) {
        if (base == null) return null;
        String stem = FAMILY_ALIAS.getOrDefault(base, base);
        List<String> candidates = new ArrayList<>();
        candidates.add(stem + suffix);
        if (stem.endsWith("bricks") || stem.endsWith("tiles")) {
            candidates.add(stem.substring(0, stem.length() - 1) + suffix);
        }
        if (stem.endsWith("_block") || stem.endsWith("_planks")) {
            candidates.add(stem.substring(0, stem.lastIndexOf('_')) + suffix);
        }
        for (String c : candidates) {
            if (exists.test(c)) return c;
        }
        return null;
    }
}
