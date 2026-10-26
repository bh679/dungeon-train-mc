package games.brennan.dungeontrain.perks;

import net.minecraft.advancements.AdvancementType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class UnlockPointLedgerTest {

    @Test
    @DisplayName("frame sets the base; only a hidden Secret Menu advancement adds one; capstones are 10")
    void weights() {
        assertEquals(1, UnlockPointLedger.weight(AdvancementType.TASK, false, false));
        assertEquals(2, UnlockPointLedger.weight(AdvancementType.GOAL, false, false));
        assertEquals(3, UnlockPointLedger.weight(AdvancementType.CHALLENGE, false, false));
        assertEquals(4, UnlockPointLedger.weight(AdvancementType.CHALLENGE, true, false));
        assertEquals(10, UnlockPointLedger.weight(AdvancementType.CHALLENGE, false, true));
        assertEquals(10, UnlockPointLedger.weight(AdvancementType.TASK, true, true));
    }

    @Test
    @DisplayName("balance never goes negative after a revoke")
    void availableClamps() {
        assertEquals(7, UnlockPointLedger.available(12, 5));
        assertEquals(0, UnlockPointLedger.available(3, 5));
    }
}
