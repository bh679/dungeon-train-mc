package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What a build's blocks say the reviewer will want explained, read before its author is asked for a
 * note: the <b>redstone</b> worth describing, and the <b>loot</b>. Each comes back as the blocks that
 * earned the question, so the screen can show the author exactly what it is asking about.
 *
 * <p>Redstone means the parts that make a machine — repeaters, comparators, observers, pistons,
 * droppers, dispensers, calibrated sculk sensors ({@link #ADVANCED_REDSTONE}). Dust, levers and
 * buttons are left out on purpose: they are placed as decoration far more often than as circuitry,
 * and asking every author with a lever on the wall how their redstone works would teach them to skip
 * the question.</p>
 *
 * <p>Loot means anything {@link TemplateLoot} finds except a {@link TemplateLoot.Source#DEFAULT} —
 * that one is the fallback prefab rolled into any empty container of a covered type, so every build
 * with an empty chest would count — plus any {@link #VALUABLE_BLOCKS} block, which is loot in all but
 * name: a diamond block is a pickaxe away from nine diamonds. Most valuable first.</p>
 *
 * <p>Both lists are block tags, so what counts can be widened in data without a code change.</p>
 */
public final class SubmitHints {

    /** Blocks that make a build's redstone worth asking about. */
    public static final TagKey<Block> ADVANCED_REDSTONE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "advanced_redstone"));

    /** Blocks that count as loot by themselves. */
    public static final TagKey<Block> VALUABLE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "valuable_blocks"));

    /** Where a found block's loot comes from, for its tooltip. */
    public enum Kind { REDSTONE, VALUABLE, POOL, PREFAB, INLINE, TABLE }

    /**
     * One kind of block that earned a question, and how many of it the build has.
     *
     * @param detail the loot table or prefab id for {@link Kind#TABLE} / {@link Kind#PREFAB}, else empty
     * @param value  how much it is worth, all of them together — the order the screen shows them in
     */
    public record Found(Block block, int count, Kind kind, String detail, double value) {
        public Found {
            detail = detail == null ? "" : detail;
        }
    }

    /** Which extra questions a build earns, as the blocks that earned them. {@link #NONE} asks only the general one. */
    public record Hints(List<Found> redstone, List<Found> loot) {
        public static final Hints NONE = new Hints(List.of(), List.of());

        public Hints {
            redstone = redstone == null ? List.of() : List.copyOf(redstone);
            loot = loot == null ? List.of() : List.copyOf(loot);
        }

        public boolean hasRedstone() {
            return !redstone.isEmpty();
        }

        public boolean hasLoot() {
            return !loot.isEmpty();
        }
    }

    /**
     * What one valuable block is worth next to the others, roughly by what it is made of. The loot
     * ranking ({@link LootValue}) scores items by what they do, and a storage block does nothing — it
     * would rank a netherite block level with a gold one.
     */
    private static final Map<Block, Double> BLOCK_WORTH = Map.of(
            Blocks.NETHERITE_BLOCK, 100.0,
            Blocks.BEACON, 80.0,
            Blocks.DIAMOND_BLOCK, 50.0,
            Blocks.ANCIENT_DEBRIS, 40.0,
            Blocks.EMERALD_BLOCK, 30.0,
            Blocks.GOLD_BLOCK, 15.0);

    /** A tagged valuable this map does not know — added to the tag in data, say. */
    private static final double DEFAULT_WORTH = 10.0;

    private SubmitHints() {}

    /** Read a template, with its loot already judged (so sidecar-aware callers keep their stores). */
    public static Hints of(StructureTemplate template, List<TemplateLoot.LootBlock> loot) {
        return of(TemplateCells.blockInfos(template), loot,
                state -> state.is(ADVANCED_REDSTONE), state -> state.is(VALUABLE_BLOCKS));
    }

    /**
     * The decision itself, over blocks already read out and the predicates that name the two lists —
     * split out so it can be tested without the tags being bound.
     */
    static Hints of(List<StructureTemplate.StructureBlockInfo> blocks, List<TemplateLoot.LootBlock> loot,
                    Predicate<BlockState> redstone, Predicate<BlockState> valuable) {
        Map<Block, Integer> redstoneCounts = new LinkedHashMap<>();
        Map<Block, Integer> valuableCounts = new LinkedHashMap<>();
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockState state = info.state();
            if (state == null || state.isAir()) continue;
            if (redstone.test(state)) redstoneCounts.merge(state.getBlock(), 1, Integer::sum);
            if (valuable.test(state)) valuableCounts.merge(state.getBlock(), 1, Integer::sum);
        }

        List<Found> machine = new ArrayList<>();
        redstoneCounts.forEach((block, n) -> machine.add(new Found(block, n, Kind.REDSTONE, "", n)));
        machine.sort(Comparator.comparingDouble(Found::value).reversed());

        List<Found> worth = new ArrayList<>();
        if (loot != null) {
            for (TemplateLoot.LootBlock block : loot) {
                Kind kind = kindOf(block.source());
                if (kind != null) worth.add(new Found(block.block(), block.count(), kind, block.detail(), block.total()));
            }
        }
        valuableCounts.forEach((block, n) -> worth.add(new Found(block, n, Kind.VALUABLE, "",
                n * BLOCK_WORTH.getOrDefault(block, DEFAULT_WORTH))));
        worth.sort(Comparator.comparingDouble(Found::value).reversed());
        return new Hints(machine, worth);
    }

    /** The tooltip kind for a loot source, or null for the empty-container fallback, which is not loot. */
    private static Kind kindOf(TemplateLoot.Source source) {
        return switch (source) {
            case POOL -> Kind.POOL;
            case PREFAB -> Kind.PREFAB;
            case INLINE -> Kind.INLINE;
            case TABLE -> Kind.TABLE;
            case DEFAULT -> null;
        };
    }
}
