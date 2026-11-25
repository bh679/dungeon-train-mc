package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the villager job mode mob-row option ({@link VariantVillager}): parsing, the click
 * cycle, which rows may carry it, and the sidecar round-trip.
 */
final class VariantVillagerTest {

    private static final ResourceLocation VILLAGER = ResourceLocation.parse(VariantVillager.ID_VILLAGER);
    private static final ResourceLocation ZOMBIE = ResourceLocation.parse("minecraft:zombie");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("tokens parse case-insensitively; unknown tokens and ordinals fall back")
    void parseAndOrdinals() {
        assertEquals(VariantVillager.Mode.RANDOM, VariantVillager.Mode.parse(" Random "));
        assertEquals(VariantVillager.Mode.NITWIT, VariantVillager.Mode.parse("nitwit"));
        assertEquals(VariantVillager.Mode.JOB, VariantVillager.Mode.parse("job"));
        assertNull(VariantVillager.Mode.parse("farmer"));
        assertEquals(VariantVillager.Mode.JOB, VariantVillager.Mode.fromOrdinal(7));
        assertEquals(VariantVillager.Mode.NITWIT, VariantVillager.Mode.fromOrdinal(2));
    }

    @Test
    @DisplayName("a click steps J -> R -> N -> J")
    void cycle() {
        assertEquals(VariantVillager.Mode.RANDOM, VariantVillager.Mode.JOB.next());
        assertEquals(VariantVillager.Mode.NITWIT, VariantVillager.Mode.RANDOM.next());
        assertEquals(VariantVillager.Mode.JOB, VariantVillager.Mode.NITWIT.next());
    }

    @Test
    @DisplayName("only a villager mob row keeps a job mode")
    void onlyVillagerRows() {
        assertEquals(VariantVillager.Mode.NITWIT,
            VariantState.ofMob(VILLAGER, null, 1, VariantRotation.NONE)
                .withVillager(VariantVillager.Mode.NITWIT).villager());
        assertEquals(VariantVillager.Mode.JOB,
            VariantState.ofMob(ZOMBIE, null, 1, VariantRotation.NONE)
                .withVillager(VariantVillager.Mode.NITWIT).villager());
        assertEquals(VariantVillager.Mode.JOB,
            VariantState.ofMob(VILLAGER, null, 1, VariantRotation.NONE).withVillager(null).villager());
    }

    @Test
    @DisplayName("Random and Nitwit survive the sidecar JSON; Job stays out of it")
    void sidecarRoundTrip() {
        List<VariantState> cell = List.of(
            VariantState.ofMob(VILLAGER, null, 10, VariantRotation.NONE).withVillager(VariantVillager.Mode.RANDOM),
            VariantState.ofMob(VILLAGER, null, 1, VariantRotation.NONE).withVillager(VariantVillager.Mode.NITWIT),
            VariantState.ofMob(VILLAGER, null, 1, VariantRotation.NONE));
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendCellJson(sb, cell, 0);
        String json = sb.toString();
        assertTrue(json.contains("\"villager\": \"random\""), json);
        assertTrue(json.contains("\"villager\": \"nitwit\""), json);
        assertEquals(2, json.split("\"villager\"", -1).length - 1, "Job writes nothing: " + json);

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
            JsonParser.parseString(json), blocks, "test", BlockPos.ZERO);
        assertNotNull(parsed);
        assertEquals(VariantVillager.Mode.RANDOM, parsed.states().get(0).villager());
        assertEquals(VariantVillager.Mode.NITWIT, parsed.states().get(1).villager());
        assertEquals(VariantVillager.Mode.JOB, parsed.states().get(2).villager());
    }

    @Test
    @DisplayName("a bad villager token reads as Job")
    void badTokenIsJob() {
        assertEquals(VariantVillager.Mode.JOB, CarriageVariantBlocks.parseVillager(
            JsonParser.parseString("\"banker\""), "test", BlockPos.ZERO));
        assertEquals(VariantVillager.Mode.JOB, CarriageVariantBlocks.parseVillager(null, "test", BlockPos.ZERO));
    }
}
