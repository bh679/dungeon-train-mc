package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.editor.TemplateCells;
import games.brennan.dungeontrain.editor.TemplateLoot;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * The numbers a template's data sheet shows, counted once when its tile is baked.
 *
 * @param blocks        non-air blocks in the template
 * @param declaredSize  the size the file declares, which may be larger than the blocks occupy
 * @param blockEntities blocks carrying NBT (signs, spawners, containers, ...)
 * @param containers    the subset of those holding an item list
 * @param entities      entities saved with the template
 * @param lights        the blocks that give off light, one entry per type, most numerous first
 * @param loot          the blocks that hand out loot, most valuable first
 * @param topBlock      the most numerous block in the template — its one-icon signature — or null
 * @param solidCounts   how many of each full-cube block the template has, so a set of templates
 *                      (a tunnel group) can find its own most numerous block
 * @param blockCounts   every block, one entry per kind, most numerous first — the Blocks page
 */
public record TemplateSummary(int blocks, Vec3i declaredSize, int blockEntities, int containers,
                              int entities, List<TemplateCells.LightBlock> lights,
                              List<TemplateLoot.LootBlock> loot, Block topBlock,
                              java.util.Map<Block, Integer> solidCounts,
                              List<TemplateCells.BlockCount> blockCounts) {

    /** The empty sheet — a template that could not be read. */
    public static final TemplateSummary NONE = new TemplateSummary(0, Vec3i.ZERO, 0, 0, 0, List.of(), List.of());

    public TemplateSummary {
        lights = lights == null ? List.of() : List.copyOf(lights);
        loot = loot == null ? List.of() : List.copyOf(loot);
        solidCounts = solidCounts == null ? java.util.Map.of() : java.util.Map.copyOf(solidCounts);
        blockCounts = blockCounts == null ? List.of() : List.copyOf(blockCounts);
    }

    /** A summary with no per-block tally — previews that never show a Blocks page. */
    public TemplateSummary(int blocks, Vec3i declaredSize, int blockEntities, int containers,
                           int entities, List<TemplateCells.LightBlock> lights, List<TemplateLoot.LootBlock> loot,
                           Block topBlock, java.util.Map<Block, Integer> solidCounts) {
        this(blocks, declaredSize, blockEntities, containers, entities, lights, loot, topBlock, solidCounts, List.of());
    }

    /** The shape from before the solid tallies were kept. */
    public TemplateSummary(int blocks, Vec3i declaredSize, int blockEntities, int containers,
                           int entities, List<TemplateCells.LightBlock> lights, List<TemplateLoot.LootBlock> loot,
                           Block topBlock) {
        this(blocks, declaredSize, blockEntities, containers, entities, lights, loot, topBlock, java.util.Map.of(), List.of());
    }

    /** The shape from before the top block was tallied. */
    public TemplateSummary(int blocks, Vec3i declaredSize, int blockEntities, int containers,
                           int entities, List<TemplateCells.LightBlock> lights, List<TemplateLoot.LootBlock> loot) {
        this(blocks, declaredSize, blockEntities, containers, entities, lights, loot, null, java.util.Map.of(), List.of());
    }

    /** How many of each full-cube block {@code states} holds. */
    public static java.util.Map<Block, Integer> solidCountsOf(
            java.util.Collection<net.minecraft.world.level.block.state.BlockState> states) {
        java.util.Map<Block, Integer> counts = new java.util.HashMap<>();
        for (net.minecraft.world.level.block.state.BlockState s : states) {
            if (s == null || s.isAir()) continue;
            if (!s.isCollisionShapeFullBlock(
                    net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO)) continue;
            counts.merge(s.getBlock(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * The most numerous <b>solid</b> block of {@code states} — full cubes only, so a template's
     * stairs, slabs, walls and trapdoors never stand in for what it is built of — falling back to the
     * most numerous block of any shape when it has no full cube. Null when there is none. Ties go to
     * the first seen.
     */
    public static Block topBlockOf(java.util.Collection<net.minecraft.world.level.block.state.BlockState> states) {
        Block solid = topBlockOf(states, true);
        return solid != null ? solid : topBlockOf(states, false);
    }

    private static Block topBlockOf(java.util.Collection<net.minecraft.world.level.block.state.BlockState> states,
                                    boolean solidOnly) {
        java.util.Map<Block, Integer> counts = new java.util.LinkedHashMap<>();
        for (net.minecraft.world.level.block.state.BlockState s : states) {
            if (s == null || s.isAir()) continue;
            if (solidOnly && !s.isCollisionShapeFullBlock(
                    net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO)) continue;
            counts.merge(s.getBlock(), 1, Integer::sum);
        }
        Block top = null;
        int best = 0;
        for (java.util.Map.Entry<Block, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                top = e.getKey();
            }
        }
        return top;
    }

    public boolean isEmpty() {
        return blocks == 0;
    }
}
