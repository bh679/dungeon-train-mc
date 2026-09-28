package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.LostCityStretchProcessor.Plan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LostCityStretchProcessor}: the band is found along each axis, repeated or removed, roof, plinth and
 * pad kept, the plan consistent with {@link LostCityFootprint}. Needs the bootstrap.
 */
final class LostCityStretchProcessorTest {

    /** Floors of period 5 up (a stone slab then four glass-walled layers); bays of period 4 along x. */
    private static final int PERIOD = 5;
    private static final int BAY = 4;

    /** A 9-deep tower on a solid plinth with a gold roof, {@code bays} bays wide, in template-local coordinates. */
    private static List<StructureBlockInfo> tower(int floors, int bays) {
        List<StructureBlockInfo> out = new ArrayList<>();
        int height = 3 + floors * PERIOD + 2;
        int width = 2 + bays * BAY + 3;      // plaza margins of 2 and 3
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < 9; z++) {
                    boolean plaza = x < 2 || x >= width - 3;
                    boolean edge = z == 0 || z == 8 || x == 2 || x == width - 4;
                    boolean pier = (x - 2) % BAY == 0;   // a solid stone cross-wall every bay
                    Block b;
                    if (y == 0) b = Blocks.GRASS_BLOCK;
                    else if (plaza) b = Blocks.AIR;
                    else if (y < 3) b = Blocks.BRICKS;
                    else if (y >= height - 2) b = y == height - 1 ? Blocks.GOLD_BLOCK : Blocks.STONE;
                    else if ((y - 3) % PERIOD == 0) b = Blocks.STONE;
                    else if (pier) b = Blocks.STONE;
                    else if (edge) b = Blocks.GLASS;
                    else b = Blocks.AIR;
                    out.add(new StructureBlockInfo(new BlockPos(x, y, z), b.defaultBlockState(), null));
                }
            }
        }
        return out;
    }

    /** The template placed unrotated at {@code origin}: world positions. */
    private static List<StructureBlockInfo> placed(List<StructureBlockInfo> template, BlockPos origin) {
        List<StructureBlockInfo> out = new ArrayList<>(template.size());
        for (StructureBlockInfo i : template) out.add(new StructureBlockInfo(i.pos().offset(origin), i.state(), null));
        return out;
    }

    private static int count(List<StructureBlockInfo> list, Block block) {
        int n = 0;
        for (StructureBlockInfo i : list) if (i.state().getBlock() == block) n++;
        return n;
    }

    private static int extent(List<StructureBlockInfo> list, BlockPos origin, Direction.Axis axis) {
        int t = 0;
        for (StructureBlockInfo i : list) t = Math.max(t, axis.choose(i.pos().getX(), i.pos().getY(), i.pos().getZ()) - axis.choose(origin.getX(), origin.getY(), origin.getZ()));
        return t;
    }

    /** How many layers hold glass: one per wall layer of every floor. */
    private static int glassLayers(List<StructureBlockInfo> list) {
        java.util.Set<Integer> ys = new java.util.HashSet<>();
        for (StructureBlockInfo i : list) if (i.state().getBlock() == Blocks.GLASS) ys.add(i.pos().getY());
        return ys.size();
    }

    /** The building's own far x edge (its wall), not the plaza's. */
    private static int wallX(int bays) {
        return 2 + bays * BAY + 3 - 4;
    }

    private static LostCityStretchProcessor stretch(int min, int max, Direction.Axis axis) {
        return new LostCityStretchProcessor(min, max, 3, 12, 0.85F, axis);
    }

    private static List<StructureBlockInfo> run(LostCityStretchProcessor p, BlockPos origin, List<StructureBlockInfo> template) {
        Plan plan = p.plan(origin, template);
        assertNotNull(plan, "a plan");
        return p.apply(plan, origin, LostCityStretchProcessor.plain(), template, placed(template, origin));
    }

    @Test
    @DisplayName("taller: the floor band repeats, the roof rises by whole floors, the plinth and pad stay put")
    void taller() {
        BlockPos origin = new BlockPos(64, 70, 64);
        List<StructureBlockInfo> template = tower(6, 5);
        List<StructureBlockInfo> before = placed(template, origin);
        List<StructureBlockInfo> after = run(stretch(3, 3, Direction.Axis.Y), origin, template);
        assertEquals(extent(before, origin, Direction.Axis.Y) + 3 * PERIOD, extent(after, origin, Direction.Axis.Y));
        assertEquals(count(before, Blocks.GOLD_BLOCK), count(after, Blocks.GOLD_BLOCK));
        assertEquals(count(before, Blocks.BRICKS), count(after, Blocks.BRICKS));
        assertEquals(count(before, Blocks.GRASS_BLOCK), count(after, Blocks.GRASS_BLOCK));
        assertTrue(count(after, Blocks.GLASS) > count(before, Blocks.GLASS));
        int goldY = -1;
        for (StructureBlockInfo i : after) if (i.state().getBlock() == Blocks.GOLD_BLOCK) goldY = i.pos().getY() - origin.getY();
        assertEquals(extent(after, origin, Direction.Axis.Y), goldY, "the roof is still on top");
        assertEquals(after, run(stretch(3, 3, Direction.Axis.Y), origin, template), "deterministic");
    }

    @Test
    @DisplayName("shorter: floors are removed, never below one floor of the band")
    void shorter() {
        BlockPos origin = new BlockPos(0, 64, 0);
        List<StructureBlockInfo> template = tower(6, 5);
        List<StructureBlockInfo> before = placed(template, origin);
        List<StructureBlockInfo> after = run(stretch(-2, -2, Direction.Axis.Y), origin, template);
        assertEquals(extent(before, origin, Direction.Axis.Y) - 2 * PERIOD, extent(after, origin, Direction.Axis.Y));
        assertEquals(count(before, Blocks.GOLD_BLOCK), count(after, Blocks.GOLD_BLOCK));
        Plan greedy = stretch(-16, -16, Direction.Axis.Y).plan(origin, tower(4, 5));
        assertNotNull(greedy);
        assertTrue(greedy.delta() >= -3, "one floor of the band is kept: " + greedy.delta());
    }

    @Test
    @DisplayName("wider: the bay repeats along x, the plaza and pad stretch with it, and the growth matches the plan")
    void wider() {
        BlockPos origin = new BlockPos(-100, 64, 200);
        LostCityStretchProcessor p = stretch(2, 2, Direction.Axis.X);
        List<StructureBlockInfo> template = tower(4, 6);
        Plan plan = p.plan(origin, template);
        assertNotNull(plan);
        assertEquals(BAY, plan.band().period());
        assertEquals(2 * BAY, plan.growth());
        List<StructureBlockInfo> before = placed(template, origin);
        List<StructureBlockInfo> after = p.apply(plan, origin, LostCityStretchProcessor.plain(), template, before);
        assertEquals(extent(before, origin, Direction.Axis.X) + 2 * BAY, extent(after, origin, Direction.Axis.X));
        assertEquals(extent(before, origin, Direction.Axis.Y), extent(after, origin, Direction.Axis.Y));
        assertEquals(count(before, Blocks.GRASS_BLOCK) + 2 * BAY * 9, count(after, Blocks.GRASS_BLOCK), "the pad grew too");
        assertEquals(count(before, Blocks.GOLD_BLOCK) + 2 * BAY * 9, count(after, Blocks.GOLD_BLOCK), "so did the roof");
        List<StructureBlockInfo> narrower = run(stretch(-2, -2, Direction.Axis.X), origin, template);
        assertEquals(extent(before, origin, Direction.Axis.X) - 2 * BAY, extent(narrower, origin, Direction.Axis.X));
    }

    @Test
    @DisplayName("a rotated placement stretches along the world axis the template's x maps to")
    void rotated() {
        BlockPos origin = new BlockPos(10, 64, 10);
        LostCityStretchProcessor p = stretch(1, 1, Direction.Axis.X);
        List<StructureBlockInfo> template = tower(3, 5);
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(Rotation.CLOCKWISE_90);
        List<StructureBlockInfo> placed = new ArrayList<>();
        for (StructureBlockInfo i : template) {
            placed.add(new StructureBlockInfo(StructureTemplate.calculateRelativePosition(settings, i.pos()).offset(origin), i.state(), null));
        }
        Plan plan = p.plan(origin, template);
        assertNotNull(plan);
        List<StructureBlockInfo> after = p.apply(plan, origin, settings, template, placed);
        int zBefore = extent(placed, origin, Direction.Axis.Z), zAfter = extent(after, origin, Direction.Axis.Z);
        assertEquals(zBefore + BAY, zAfter, "template x became world +z");
        assertEquals(extent(placed, origin, Direction.Axis.X), extent(after, origin, Direction.Axis.X));
        BoundingBox box = new BoundingBox(0, 64, 0, 8, 100, 30);
        BoundingBox grown = LostCityFootprint.grown(box, Direction.Axis.X, Rotation.CLOCKWISE_90, BAY);
        assertEquals(30 + BAY, grown.maxZ());
        assertEquals(8, grown.maxX());
        BoundingBox shrunk = LostCityFootprint.grown(box, Direction.Axis.X, Rotation.NONE, -BAY);
        assertEquals(8 - BAY, shrunk.maxX());
        BoundingBox back = LostCityFootprint.grown(box, Direction.Axis.X, Rotation.CLOCKWISE_180, BAY);
        assertEquals(-BAY, back.minX(), "rotated 180, template +x is world -x");
    }

    @Test
    @DisplayName("ceilings: every floor gains or loses layers, the roof follows, and the floor count stays")
    void ceilings() {
        BlockPos origin = new BlockPos(5, 64, 5);
        List<StructureBlockInfo> template = tower(6, 5);
        List<StructureBlockInfo> before = placed(template, origin);
        LostCityStretchProcessor higher = new LostCityStretchProcessor(0, 0, 3, 12, 0.85F, Direction.Axis.Y, 2, 0, 4096);
        Plan plan = higher.plan(origin, template);
        assertNotNull(plan);
        assertEquals(2, plan.floorLayers());
        assertEquals(2 * plan.band().count(), plan.growth());
        List<StructureBlockInfo> after = higher.apply(plan, origin, LostCityStretchProcessor.plain(), template, before);
        assertEquals(extent(before, origin, Direction.Axis.Y) + plan.growth(), extent(after, origin, Direction.Axis.Y));
        assertEquals(count(before, Blocks.GOLD_BLOCK), count(after, Blocks.GOLD_BLOCK));
        assertEquals(glassLayers(before) + 2 * plan.band().count(), glassLayers(after), "every floor gained two wall layers");
        LostCityStretchProcessor lower = new LostCityStretchProcessor(0, 0, 3, 12, 0.85F, Direction.Axis.Y, -1, 0, 4096);
        Plan low = lower.plan(origin, template);
        assertNotNull(low);
        List<StructureBlockInfo> squat = lower.apply(low, origin, LostCityStretchProcessor.plain(), template, before);
        assertEquals(extent(before, origin, Direction.Axis.Y) - low.band().count(), extent(squat, origin, Direction.Axis.Y));
        assertEquals(glassLayers(before) - low.band().count(), glassLayers(squat), "every floor lost one wall layer");
        assertEquals(count(before, Blocks.GOLD_BLOCK), count(squat, Blocks.GOLD_BLOCK));
        LostCityStretchProcessor tooLow = new LostCityStretchProcessor(0, 0, 3, 12, 0.85F, Direction.Axis.Y, -8, 0, 4096);
        Plan clamped = tooLow.plan(origin, template);
        assertNotNull(clamped);
        assertEquals(-(PERIOD - 3), clamped.floorLayers(), "a floor keeps three layers");
    }

    @Test
    @DisplayName("podium and setback: only the layers in range move, and the box grows only for a podium")
    void podiumAndSetback() {
        BlockPos origin = new BlockPos(0, 64, 0);
        List<StructureBlockInfo> template = tower(6, 5);
        List<StructureBlockInfo> before = placed(template, origin);
        LostCityStretchProcessor podium = new LostCityStretchProcessor(2, 2, 3, 12, 0.85F, Direction.Axis.X, 0, 0, 12);
        Plan plan = podium.plan(origin, template);
        assertNotNull(plan);
        assertEquals(2 * BAY, plan.growth(), "a podium widens the box");
        List<StructureBlockInfo> after = podium.apply(plan, origin, LostCityStretchProcessor.plain(), template, before);
        int lowMax = 0, highMax = 0;
        for (StructureBlockInfo i : after) {
            int y = i.pos().getY() - origin.getY();
            if (i.state().isAir()) continue;
            if (y <= 12) lowMax = Math.max(lowMax, i.pos().getX()); else highMax = Math.max(highMax, i.pos().getX());
        }
        assertEquals(extent(before, origin, Direction.Axis.X) + 2 * BAY, lowMax, "the base is wider");
        assertTrue(highMax <= extent(before, origin, Direction.Axis.X), "the upper floors are not");
        LostCityStretchProcessor setback = new LostCityStretchProcessor(-2, -2, 3, 12, 0.85F, Direction.Axis.X, 0, 13, 4096);
        Plan back = setback.plan(origin, template);
        assertNotNull(back);
        assertEquals(0, back.growth(), "a setback keeps the full base, so the box stays");
        List<StructureBlockInfo> stepped = setback.apply(back, origin, LostCityStretchProcessor.plain(), template, before);
        int upper = 0;
        for (StructureBlockInfo i : stepped) {
            if (i.pos().getY() - origin.getY() >= 13 && !i.state().isAir()) upper = Math.max(upper, i.pos().getX());
        }
        assertEquals(wallX(5) - 2 * BAY, upper, "the upper floors are narrower");
    }

    @Test
    @DisplayName("a building with no repeat, or nothing to change, gets no plan")
    void noPlan() {
        List<StructureBlockInfo> cone = new ArrayList<>();          // every layer differs from the one above
        for (int y = 0; y < 40; y++) {
            int r = 20 - y / 2;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    cone.add(new StructureBlockInfo(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), null));
                }
            }
        }
        assertNull(stretch(1, 2, Direction.Axis.Y).plan(BlockPos.ZERO, cone));
        assertNull(stretch(-3, -1, Direction.Axis.Z).plan(BlockPos.ZERO, tower(4, 5)), "no bays along z");
    }
}
