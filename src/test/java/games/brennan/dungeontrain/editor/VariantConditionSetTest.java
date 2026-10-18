package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VariantConditionSet} as data: the If list's token (and that a single condition's token is
 * unchanged), its sidecar JSON, list edits, and how it turns with a plot. Block rows' Level band
 * round-trips here too. Resolution lives in {@link VariantConditionResolveTest}.
 */
final class VariantConditionSetTest {

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

    private static final VariantCondition DOWN_OAK = VariantCondition.NONE.withValueAdded(OAK);
    private static final VariantCondition GROUP_STONE = VariantCondition.NONE.withGroup(3).withValueAdded(STONE);

    @Test
    @DisplayName("one condition's token is exactly the condition's own token, so old sidecars read and write unchanged")
    void singleTokenUnchanged() {
        assertEquals(DOWN_OAK.id(), VariantConditionSet.of(DOWN_OAK).id());
        assertEquals(DOWN_OAK.id(), VariantConditionSet.of(DOWN_OAK).storedId());
        String old = "{\"state\": \"minecraft:glass\", \"if\": \"down=minecraft:oak_planks\"}";
        assertEquals(old, write(parse(old)));
    }

    @Test
    @DisplayName("a list token round-trips its conditions and AND / OR")
    void listRoundTrip() {
        VariantConditionSet and = VariantConditionSet.of(DOWN_OAK).withAdded(GROUP_STONE);
        assertEquals("down=minecraft:oak_planks;group3=minecraft:stone", and.id());
        assertEquals(and, VariantConditionSet.parse(and.id()));
        VariantConditionSet or = and.withAny(true);
        assertEquals("any|" + and.id(), or.id());
        assertEquals(or, VariantConditionSet.parse(or.id()));
        assertEquals(VariantConditionSet.NONE, VariantConditionSet.parse(""));
        assertEquals(VariantConditionSet.NONE, VariantConditionSet.parse(null));
    }

    @Test
    @DisplayName("empty conditions sync but never gate or reach the sidecar")
    void emptyConditionsStayOffDisk() {
        VariantConditionSet withDraft = VariantConditionSet.of(DOWN_OAK).withAdded(VariantCondition.NONE).withAny(true);
        assertEquals(2, VariantConditionSet.parse(withDraft.id()).size());
        assertEquals(DOWN_OAK.id(), withDraft.storedId(), "draft dropped; OR of one is just the one");
        assertTrue(VariantConditionSet.of(VariantCondition.NONE).isDefault());
        assertEquals("", VariantConditionSet.of(VariantCondition.NONE).storedId());
    }

    @Test
    @DisplayName("list edits: append one past the end, replace, remove, cap at MAX_CONDITIONS")
    void listEdits() {
        VariantConditionSet set = VariantConditionSet.NONE.with(0, DOWN_OAK);
        assertEquals(List.of(DOWN_OAK), set.conditions());
        set = set.with(0, GROUP_STONE);
        assertEquals(List.of(GROUP_STONE), set.conditions());
        assertSame(set, set.with(5, DOWN_OAK), "a gap is refused");
        assertEquals(VariantConditionSet.NONE.conditions(), set.withRemoved(0).conditions());
        VariantConditionSet full = VariantConditionSet.NONE;
        for (int i = 0; i < VariantConditionSet.MAX_CONDITIONS; i++) full = full.withAdded(DOWN_OAK);
        assertSame(full, full.withAdded(GROUP_STONE));
        assertEquals(VariantConditionSet.MAX_CONDITIONS - 1, full.withRemoved(0).size());
    }

    @Test
    @DisplayName("rotating and mirroring turn every touching condition; groups stay put")
    void turnsEveryMember() {
        VariantCondition north = VariantCondition.NONE.withDirection(Direction.NORTH).withValueAdded(OAK);
        VariantConditionSet set = VariantConditionSet.of(north).withAdded(GROUP_STONE).withAny(true);
        VariantConditionSet turned = set.rotated(Rotation.CLOCKWISE_90);
        assertEquals(List.of(Direction.EAST), turned.get(0).sides());
        assertEquals(GROUP_STONE, turned.get(1));
        assertTrue(turned.any());
        assertEquals(List.of(Direction.SOUTH), set.reflected(false, false, true).get(0).sides());
        VariantConditionSet groupsOnly = VariantConditionSet.of(GROUP_STONE);
        assertSame(groupsOnly, groupsOnly.rotated(Rotation.CLOCKWISE_90));
    }

    @Test
    @DisplayName("a block row's Level band and If list round-trip through the sidecar")
    void blockLevelJson() {
        VariantState glass = VariantState.of(Blocks.GLASS.defaultBlockState())
            .withCondition(VariantConditionSet.of(DOWN_OAK).withAdded(GROUP_STONE).withAny(true))
            .withDifficulty(new VariantDifficulty(2, 9));
        assertTrue(glass.isConditional());
        VariantState back = parse(write(glass));
        assertEquals(glass.condition(), back.condition());
        assertEquals(glass.difficulty(), back.difficulty());

        VariantState bandOnly = VariantState.of(Blocks.GLASS.defaultBlockState())
            .withDifficulty(new VariantDifficulty(4, VariantDifficulty.ALL));
        assertTrue(bandOnly.isConditional());
        assertFalse(bandOnly.isPlainBareString());
        assertEquals(bandOnly.difficulty(), parse(write(bandOnly)).difficulty());
        assertEquals("\"minecraft:glass\"", write(VariantState.of(Blocks.GLASS.defaultBlockState())));
    }

    @Test
    @DisplayName("isLive fails when any group-targeted condition lost its group")
    void liveness() {
        VariantConditionSet set = VariantConditionSet.of(DOWN_OAK).withAdded(GROUP_STONE);
        assertTrue(VariantConditionEdits.isLive(set, java.util.Set.of(3), 1));
        assertFalse(VariantConditionEdits.isLive(set, java.util.Set.of(4), 1));
    }
}
