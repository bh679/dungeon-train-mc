package games.brennan.dungeontrain.trader;

import games.brennan.dungeontrain.trader.WanderingTraderStock.Ware;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WanderingTraderStockTest {

    private static final List<Ware> ALL = List.of(Ware.values());

    @Test
    void weightsAreTheAgreedShares() {
        assertEquals(13, WanderingTraderStock.totalWeight(ALL));
        assertEquals(5, Ware.CAMERA.weight);
        assertEquals(3, Ware.EDIBLE_BACKPACK.weight);
        assertEquals(1, Ware.GOLDEN_EDIBLE_BACKPACK.weight);
        assertEquals(3, Ware.PHOTO_ALBUM.weight);
        assertEquals(1, Ware.LIVE_EYE.weight);
    }

    @Test
    void everyRollLandsOnAWareInProportionToItsWeight() {
        int[] landed = new int[Ware.values().length];
        for (int roll = 0; roll < WanderingTraderStock.totalWeight(ALL); roll++) {
            landed[WanderingTraderStock.pick(roll, ALL).ordinal()]++;
        }
        for (Ware ware : ALL) {
            assertEquals(ware.weight, landed[ware.ordinal()], ware.name());
        }
    }

    @Test
    void aMissingWareLeavesTheRollToTheRest() {
        List<Ware> withoutBackpacks = List.of(Ware.CAMERA, Ware.PHOTO_ALBUM, Ware.LIVE_EYE);
        assertEquals(9, WanderingTraderStock.totalWeight(withoutBackpacks));
        assertEquals(Ware.CAMERA, WanderingTraderStock.pick(4, withoutBackpacks));
        assertEquals(Ware.PHOTO_ALBUM, WanderingTraderStock.pick(5, withoutBackpacks));
        assertEquals(Ware.LIVE_EYE, WanderingTraderStock.pick(8, withoutBackpacks));
    }

    @Test
    void aRollPastTheTotalIsABug() {
        assertThrows(IllegalArgumentException.class, () -> WanderingTraderStock.pick(13, ALL));
    }

    @Test
    void theGoldenBackpacksPriceIsExactlyTenEmeraldBlocks() {
        assertEquals(90, Ware.GOLDEN_EDIBLE_BACKPACK.priceEmeralds);
        assertEquals(10, WanderingTraderStock.blocksFor(Ware.GOLDEN_EDIBLE_BACKPACK.priceEmeralds));
    }
}
