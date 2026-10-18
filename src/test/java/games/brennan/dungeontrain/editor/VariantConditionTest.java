package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VariantCondition} as data: its token, its sidecar JSON, how it turns with a plot, and the
 * menu edits in {@link VariantConditionEdits}. Resolution lives in {@link VariantConditionResolveTest}.
 */
final class VariantConditionTest {

    private static final BlockPos CELL = new BlockPos(1, 1, 1);
    private static final ResourceLocation OAK = ResourceLocation.withDefaultNamespace("oak_planks");
    private static final ResourceLocation STONE = ResourceLocation.withDefaultNamespace("stone");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VariantState parse(String json) {
        VariantState out = CarriageVariantBlocks.parseVariantElement(
            JsonParser.parseString(json), BuiltInRegistries.BLOCK.asLookup(), "test", CELL);
        assertNotNull(out, "parse returned null for " + json);
        return out;
    }

    private static String write(VariantState s) {
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendVariantJson(sb, s);
        return sb.toString();
    }

    @Test
    @DisplayName("token round-trips touching and group targets with their values")
    void tokenRoundTrip() {
        VariantCondition down = VariantCondition.NONE.withValueAdded(OAK).withValueAdded(STONE);
        assertEquals("down=minecraft:oak_planks,minecraft:stone", down.id());
        assertEquals(down, VariantCondition.parse(down.id()));

        VariantCondition group = VariantCondition.NONE.withGroup(3).withValueAdded(OAK);
        assertEquals("group3=minecraft:oak_planks", group.id());
        assertEquals(group, VariantCondition.parse(group.id()));
    }

    @Test
    @DisplayName("sides are a multi-select with the band picker's clicks: toggle one, shift toggles the others")
    void sideMultiSelect() {
        VariantCondition down = VariantCondition.NONE.withValueAdded(OAK);
        VariantCondition downNorth = down.toggleSide(Direction.NORTH);
        assertEquals(List.of(Direction.DOWN, Direction.NORTH), downNorth.sides());
        assertEquals("down+north=minecraft:oak_planks", downNorth.id());
        assertEquals(downNorth, VariantCondition.parse(downNorth.id()));
        // Shift on DOWN from {DOWN}: every other side flips on.
        assertEquals(VariantCondition.ALL_SIDES, down.toggleOtherSides(Direction.DOWN).sideMask());
        // ...and again flips them back off, soloing DOWN.
        assertEquals(List.of(Direction.DOWN), down.toggleOtherSides(Direction.DOWN)
            .toggleOtherSides(Direction.DOWN).sides());
        // Clearing the last side normalises to every side, like an empty band set.
        assertEquals(VariantCondition.ALL_SIDES, down.toggleSide(Direction.DOWN).sideMask());
        assertEquals("*", down.toggleSide(Direction.DOWN).targetLabel());
    }

    @Test
    @DisplayName("unknown block ids are dropped; a malformed target is no condition")
    void parseIsForgiving() {
        VariantCondition c = VariantCondition.parse("up=minecraft:oak_planks,nosuchmod:thing");
        assertEquals(List.of(OAK), c.blocks());
        assertTrue(VariantCondition.parse("sideways=minecraft:stone").isDefault());
        assertTrue(VariantCondition.parse("group0=minecraft:stone").isDefault());
        assertTrue(VariantCondition.parse(null).isDefault());
    }

    @Test
    @DisplayName("a condition with no values is no condition, and a plain entry stays a bare string")
    void emptyIsDefault() {
        VariantState plain = VariantState.of(Blocks.STONE.defaultBlockState());
        assertTrue(plain.isPlainBareString());
        assertEquals("\"minecraft:stone\"", write(plain));
        VariantState targetOnly = plain.withCondition(VariantCondition.NONE.withDirection(Direction.UP));
        assertFalse(targetOnly.isConditional());
        assertTrue(targetOnly.isPlainBareString());
    }

    @Test
    @DisplayName("sidecar JSON carries the condition on block and mob entries")
    void jsonRoundTrip() {
        VariantCondition c = VariantCondition.NONE.withValueAdded(OAK);
        VariantState block = VariantState.of(Blocks.GLASS.defaultBlockState()).withCondition(c);
        String json = write(block);
        assertTrue(json.contains("\"if\": \"down=minecraft:oak_planks\""), json);
        assertEquals(c, parse(json).condition());

        VariantState mob = VariantState.ofMob(ResourceLocation.withDefaultNamespace("zombie"), null, 1,
            VariantRotation.NONE).withCondition(c.withGroup(2));
        assertEquals(c.withGroup(2), parse(write(mob)).condition());
    }

    @Test
    @DisplayName("matching is on block type: a rotated stair still matches, air matches the placeholder")
    void matchesBlockTypeOnly() {
        VariantCondition stairs = VariantCondition.NONE.withValueAdded(
            BuiltInRegistries.BLOCK.getKey(Blocks.OAK_STAIRS));
        assertTrue(stairs.matches(Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.WEST)));
        assertFalse(stairs.matches(Blocks.STONE.defaultBlockState()));

        VariantCondition empty = VariantCondition.NONE.withValueAdded(VariantCondition.valueFor(null));
        assertTrue(empty.matches(Blocks.AIR.defaultBlockState()));
        assertTrue(empty.matches(CarriageVariantBlocks.emptyPlaceholder()));
    }

    @Test
    @DisplayName("touching targets turn with plot rotation and mirror; group targets do not")
    void transforms() {
        VariantCondition north = VariantCondition.NONE.withDirection(Direction.NORTH).withValueAdded(OAK);
        assertEquals(List.of(Direction.EAST), north.rotated(Rotation.CLOCKWISE_90).sides());
        assertEquals(List.of(Direction.SOUTH), north.reflected(false, false, true).sides());
        assertEquals(List.of(Direction.NORTH), north.reflected(true, true, false).sides());
        VariantCondition northDown = north.toggleSide(Direction.DOWN);
        assertEquals(List.of(Direction.DOWN, Direction.UP), northDown.reflected(false, false, false)
            .withSides(VariantCondition.bit(Direction.DOWN) | VariantCondition.bit(Direction.UP)).sides());
        assertEquals(List.of(Direction.UP, Direction.SOUTH), northDown.reflected(false, true, true).sides());
        VariantCondition group = north.withGroup(4);
        assertEquals(group, group.rotated(Rotation.CLOCKWISE_90));
        assertEquals(group, group.reflected(true, true, true));

        VariantState glass = VariantState.of(Blocks.GLASS.defaultBlockState()).withCondition(north);
        assertEquals(Direction.SOUTH,
            EditorMirror.reflectVariant(glass, false, false, true).condition().sides().get(0));
    }

    @Test
    @DisplayName("edits: group stepping skips the cell's own group; held blocks add once; empty hand adds air")
    void edits() {
        Set<Integer> groups = Set.of(1, 2, 5);
        VariantConditionEdits.Result next = VariantConditionEdits.setTarget(
            VariantCondition.NONE, VariantConditionEdits.GROUP_NEXT, groups, 1);
        assertEquals(2, next.condition().groupId());
        assertEquals(5, VariantConditionEdits.setTarget(next.condition(), VariantConditionEdits.GROUP_NEXT, groups, 1)
            .condition().groupId());
        assertEquals(2, VariantConditionEdits.setTarget(
            VariantCondition.NONE.withGroup(5), VariantConditionEdits.GROUP_NEXT, groups, 1).condition().groupId());
        assertNull(VariantConditionEdits.setTarget(VariantCondition.NONE, VariantConditionEdits.GROUP_NEXT,
            Set.of(1), 1).condition());

        VariantConditionEdits.Result added = VariantConditionEdits.addHeld(VariantCondition.NONE,
            new ItemStack(Items.OAK_PLANKS));
        assertEquals(List.of(OAK), added.condition().blocks());
        assertNull(VariantConditionEdits.addHeld(added.condition(), new ItemStack(Items.OAK_PLANKS)).condition());
        assertNull(VariantConditionEdits.addHeld(added.condition(), new ItemStack(Items.DIAMOND)).condition());
        assertEquals(ResourceLocation.withDefaultNamespace("air"),
            VariantConditionEdits.addHeld(VariantCondition.NONE, ItemStack.EMPTY).condition().blocks().get(0));

        VariantCondition g2 = VariantCondition.NONE.withGroup(2);
        VariantCondition on = VariantConditionEdits.toggleValue(g2, "minecraft:oak_planks").condition();
        assertEquals(List.of(OAK), on.blocks());
        assertTrue(VariantConditionEdits.toggleValue(on, "minecraft:oak_planks").condition().blocks().isEmpty());
        assertNull(VariantConditionEdits.toggleValue(g2, "nosuchmod:thing").condition());
        assertEquals(List.of(OAK, ResourceLocation.withDefaultNamespace("air")), VariantConditionEdits.groupValues(List.of(
            VariantState.of(Blocks.OAK_PLANKS.defaultBlockState()),
            VariantState.of(Blocks.OAK_PLANKS.defaultBlockState()),
            VariantState.of(CarriageVariantBlocks.emptyPlaceholder()))));
        assertEquals(List.of(Direction.NORTH, Direction.EAST), VariantConditionEdits.setTarget(
            VariantCondition.NONE.withDirection(Direction.NORTH), "toggle:east", groups, 1).condition().sides());

        assertTrue(VariantConditionEdits.isLive(VariantCondition.NONE.withGroup(2), groups, 1));
        assertFalse(VariantConditionEdits.isLive(VariantCondition.NONE.withGroup(9), groups, 1));
    }

    @Test
    @DisplayName("Solid / Empty / Any block kinds: match by kind, round-trip, and stay out of the block values")
    void kindValues() {
        VariantCondition solid = VariantConditionEdits.toggleValue(VariantCondition.NONE,
            VariantCondition.SOLID_ID.toString()).condition();
        assertNotNull(solid);
        assertEquals("down=dungeontrain:solid", solid.id());
        assertEquals(solid, VariantCondition.parse(solid.id()));
        assertTrue(solid.matches(Blocks.STONE.defaultBlockState()));
        assertTrue(solid.matches(Blocks.OAK_PLANKS.defaultBlockState()));
        assertFalse(solid.matches(Blocks.AIR.defaultBlockState()));
        assertFalse(solid.matches(CarriageVariantBlocks.emptyPlaceholder()));
        assertFalse(solid.matches(Blocks.WATER.defaultBlockState()));
        assertFalse(solid.matches(Blocks.TORCH.defaultBlockState()));

        VariantCondition any = VariantCondition.NONE.withValueAdded(VariantCondition.ANY_ID);
        assertEquals(any, VariantCondition.parse(any.id()));
        assertTrue(any.matches(Blocks.STONE.defaultBlockState()));
        assertTrue(any.matches(Blocks.WATER.defaultBlockState()));
        assertTrue(any.matches(Blocks.TORCH.defaultBlockState()));
        assertFalse(any.matches(Blocks.AIR.defaultBlockState()));
        assertFalse(any.matches(CarriageVariantBlocks.emptyPlaceholder()));

        VariantCondition empty = VariantCondition.NONE.withValueAdded(VariantCondition.EMPTY_ID);
        assertTrue(empty.matches(CarriageVariantBlocks.emptyPlaceholder()));
        assertFalse(empty.matches(Blocks.STONE.defaultBlockState()));

        // Kinds OR with listed blocks, and the icon strip only sees the blocks.
        VariantCondition mixed = solid.withValueAdded(OAK).withValueAdded(VariantCondition.EMPTY_ID);
        assertEquals(List.of(OAK), mixed.blockValues());
        assertTrue(mixed.matches(Blocks.AIR.defaultBlockState()));
        assertTrue(mixed.matches(Blocks.STONE.defaultBlockState()));
        assertTrue(VariantConditionEdits.toggleValue(mixed, VariantCondition.SOLID_ID.toString())
            .condition().blocks().equals(List.of(OAK, VariantCondition.EMPTY_ID)));
    }
}
