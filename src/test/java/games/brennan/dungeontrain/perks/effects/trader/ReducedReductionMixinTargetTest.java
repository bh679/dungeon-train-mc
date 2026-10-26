package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.tradeeverything.trade.DemandCurve;
import games.brennan.tradeeverything.trade.DemandLedger;
import games.brennan.tradeeverything.trade.VillagerDemands;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Trade Everything seams {@code VillagerDemandsReducedReductionMixin} wraps, so a TE
 * bump that moves them fails here instead of at the first trade. The mixin's target descriptor
 * is {@code DemandLedger.withSale(String, int, double, long, DemandCurve)} inside
 * {@code VillagerDemands.recordSale(AbstractVillager, MerchantOffer)}.
 */
final class ReducedReductionMixinTargetTest {

    @Test
    @DisplayName("VillagerDemands.recordSale(AbstractVillager, MerchantOffer) is still static")
    void recordSaleSeam() throws NoSuchMethodException {
        Method m = VillagerDemands.class.getDeclaredMethod("recordSale", AbstractVillager.class, MerchantOffer.class);
        assertTrue(Modifier.isStatic(m.getModifiers()));
    }

    @Test
    @DisplayName("DemandLedger.withSale keeps the wrapped signature and stays immutable")
    void withSaleSeam() throws NoSuchMethodException {
        Method m = DemandLedger.class.getDeclaredMethod("withSale",
            String.class, int.class, double.class, long.class, DemandCurve.class);
        assertEquals(DemandLedger.class, m.getReturnType());
        DemandLedger before = DemandLedger.EMPTY;
        before.withSale("minecraft:stick", 1, 1.0, 0L, DemandCurve.defaults());
        assertTrue(before.entries().isEmpty(), "withSale must return a new ledger, not mutate");
    }
}
