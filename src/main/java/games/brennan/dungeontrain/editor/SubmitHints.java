package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;
import java.util.function.Predicate;

/**
 * What a build's blocks say the reviewer will want explained, read before its author is asked for a
 * note: whether it has <b>redstone</b> worth describing, and whether it has <b>loot</b>.
 *
 * <p>Redstone means the parts that make a machine — repeaters, comparators, observers, pistons
 * ({@link #ADVANCED_REDSTONE}). Dust, levers and buttons are left out on purpose: they are placed as
 * decoration far more often than as circuitry, and asking every author with a lever on the wall how
 * their redstone works would teach them to skip the question.</p>
 *
 * <p>Loot means anything {@link TemplateLoot} finds except a {@link TemplateLoot.Source#DEFAULT} —
 * that one is the fallback prefab rolled into any empty container of a covered type, so every build
 * with an empty chest would count — plus any {@link #VALUABLE_BLOCKS} block, which is loot in all but
 * name: a diamond block is a pickaxe away from nine diamonds.</p>
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

    /** Which extra questions a build earns. {@link #NONE} asks only the general one. */
    public record Hints(boolean redstone, boolean loot) {
        public static final Hints NONE = new Hints(false, false);
    }

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
        boolean hasRedstone = false;
        boolean hasLoot = false;
        if (loot != null) {
            for (TemplateLoot.LootBlock block : loot) {
                if (block.source() != TemplateLoot.Source.DEFAULT) {
                    hasLoot = true;
                    break;
                }
            }
        }
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockState state = info.state();
            if (state == null || state.isAir()) continue;
            if (!hasRedstone && redstone.test(state)) hasRedstone = true;
            if (!hasLoot && valuable.test(state)) hasLoot = true;
            if (hasRedstone && hasLoot) break;
        }
        return new Hints(hasRedstone, hasLoot);
    }
}
