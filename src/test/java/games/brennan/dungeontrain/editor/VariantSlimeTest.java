package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the slime size / End slime type mob-row option ({@link VariantSlime}): toggles, the
 * spawn rolls, which rows may carry it, and the sidecar round-trip.
 */
final class VariantSlimeTest {

    private static final ResourceLocation SLIME = ResourceLocation.parse(VariantSlime.ID_SLIME);
    private static final ResourceLocation END_SLIME = ResourceLocation.parse(VariantSlime.ID_END_SLIME);
    private static final ResourceLocation ZOMBIE = ResourceLocation.parse("minecraft:zombie");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("nothing lit rolls every size and every type")
    void defaultRollsEverything() {
        RandomSource random = RandomSource.create(1L);
        Set<Integer> sizes = new HashSet<>();
        Set<Integer> types = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            sizes.add(VariantSlime.NONE.rollSize(random));
            types.add(VariantSlime.NONE.rollType(random));
        }
        assertEquals(Set.of(1, 2, 3, 4), sizes);
        assertEquals(Set.of(0, 1, 2, 3), types);
        assertTrue(VariantSlime.NONE.isDefault());
    }

    @Test
    @DisplayName("lit sizes and types are the only ones rolled")
    void litValuesOnly() {
        VariantSlime slime = VariantSlime.NONE.toggledSize(2).toggledSize(4).toggledType(1);
        RandomSource random = RandomSource.create(7L);
        Set<Integer> sizes = new HashSet<>();
        Set<Integer> types = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            sizes.add(slime.rollSize(random));
            types.add(slime.rollType(random));
        }
        assertEquals(Set.of(2, 4), sizes);
        assertEquals(Set.of(1), types);
        assertEquals(VariantSlime.NONE, slime.toggledSize(2).toggledSize(4).toggledType(1));
        assertEquals(List.of("mossy"), slime.typeNames());
        assertEquals(List.of(2, 4), slime.litSizes());
    }

    @Test
    @DisplayName("only slime-type mob rows keep the option, and only an End slime keeps types")
    void rowGating() {
        VariantSlime both = VariantSlime.NONE.toggledSize(3).toggledType(2);
        assertEquals(both, VariantState.ofMob(END_SLIME, null, 1, VariantRotation.NONE).withSlime(both).slime());
        assertEquals(VariantSlime.NONE.toggledSize(3),
            VariantState.ofMob(SLIME, null, 1, VariantRotation.NONE).withSlime(both).slime());
        assertTrue(VariantState.ofMob(ZOMBIE, null, 1, VariantRotation.NONE).withSlime(both).slime().isDefault());
        assertTrue(VariantSlime.isSlime("minecraft:magma_cube"));
        assertFalse(VariantSlime.isEndSlime(SLIME));
    }

    @Test
    @DisplayName("sidecar lists parse; bad ones are refused")
    void fromLists() {
        assertEquals(VariantSlime.NONE.toggledSize(1).toggledType(3),
            VariantSlime.fromLists(List.of(1), List.of("Amber")));
        assertNull(VariantSlime.fromLists(List.of(5), null));
        assertNull(VariantSlime.fromLists(null, List.of("purple")));
    }

    @Test
    @DisplayName("slime options survive the sidecar JSON; defaults stay out of it")
    void sidecarRoundTrip() {
        VariantSlime opts = VariantSlime.NONE.toggledSize(2).toggledSize(4).toggledType(1).toggledType(2);
        List<VariantState> cell = List.of(
            VariantState.ofMob(END_SLIME, null, 1, VariantRotation.NONE).withSlime(opts),
            VariantState.ofMob(SLIME, null, 1, VariantRotation.NONE));
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendCellJson(sb, cell, 0);
        String json = sb.toString();
        assertTrue(json.contains("\"slime\": {\"sizes\": [2, 4], \"types\": [\"mossy\", \"lake\"]}"), json);
        assertEquals(1, json.split("\"slime\"", -1).length - 1, "an untouched slime writes nothing: " + json);

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
            JsonParser.parseString(json), blocks, "test", BlockPos.ZERO);
        assertNotNull(parsed);
        assertEquals(opts, parsed.states().get(0).slime());
        assertTrue(parsed.states().get(1).slime().isDefault());
    }
}
