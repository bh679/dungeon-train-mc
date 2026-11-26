package games.brennan.dungeontrain.difficulty;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureMapListingsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VillagerTrades.ItemListing[] cartographer(Map<VillagerProfession, ?> table, int level) {
        @SuppressWarnings("unchecked")
        var byLevel = (it.unimi.dsi.fastutil.ints.Int2ObjectMap<VillagerTrades.ItemListing[]>) table.get(VillagerProfession.CARTOGRAPHER);
        return byLevel.get(level);
    }

    @Test
    void vanillaCartographerLosesOnlyItsMaps() {
        VillagerTrades.ItemListing[] l2 = cartographer(VillagerTrades.TRADES, 2);
        VillagerTrades.ItemListing[] l3 = cartographer(VillagerTrades.TRADES, 3);
        assertTrue(Arrays.stream(l2).anyMatch(StructureMapListings::isStructureSearch), "L2 has the monument map");
        assertTrue(Arrays.stream(l3).anyMatch(StructureMapListings::isStructureSearch), "L3 has the mansion map");

        VillagerTrades.ItemListing[] kept2 = StructureMapListings.withoutStructureSearch(l2);
        VillagerTrades.ItemListing[] kept3 = StructureMapListings.withoutStructureSearch(l3);
        assertEquals(1, kept2.length);
        assertEquals(1, kept3.length);
        assertSame(l2[0], kept2[0], "the glass pane trade stays");
        assertSame(l3[0], kept3[0], "the compass trade stays");
    }

    @Test
    void noMapSurvivesAnyVillagerTable() {
        for (var table : List.of(VillagerTrades.TRADES, VillagerTrades.EXPERIMENTAL_TRADES)) {
            table.forEach((profession, byLevel) -> byLevel.forEach((level, listings) -> {
                VillagerTrades.ItemListing[] kept = StructureMapListings.withoutStructureSearch(listings);
                assertFalse(Arrays.stream(kept).anyMatch(l -> l instanceof VillagerTrades.TreasureMapForEmeralds),
                    profession + " L" + level);
                long nonMaps = Arrays.stream(listings).filter(l -> !StructureMapListings.isStructureSearch(l)).count();
                assertEquals(nonMaps, kept.length, profession + " L" + level + " keeps every other listing");
            }));
        }
    }

    @Test
    void untouchedPoolIsReturnedAsIs() {
        VillagerTrades.ItemListing[] l1 = cartographer(VillagerTrades.TRADES, 1);
        assertSame(l1, StructureMapListings.withoutStructureSearch(l1));
    }

    @Test
    void edgeCases() {
        assertNull(StructureMapListings.withoutStructureSearch((VillagerTrades.ItemListing[]) null));
        VillagerTrades.ItemListing[] empty = new VillagerTrades.ItemListing[0];
        assertSame(empty, StructureMapListings.withoutStructureSearch(empty));

        VillagerTrades.ItemListing map = Arrays.stream(cartographer(VillagerTrades.TRADES, 2))
            .filter(StructureMapListings::isStructureSearch).findFirst().orElseThrow();
        assertArrayEquals(new VillagerTrades.ItemListing[0],
            StructureMapListings.withoutStructureSearch(new VillagerTrades.ItemListing[]{map, map}));
        assertTrue(StructureMapListings.withoutStructureSearch(List.of(map)).isEmpty());
    }
}
