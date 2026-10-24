package games.brennan.dungeontrain.perks;

import net.minecraft.advancements.AdvancementType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class UnlockPointLedgerTest {

    @Test
    @DisplayName("frame sets the base, hidden adds one")
    void weights() {
        assertEquals(1, UnlockPointLedger.weight(AdvancementType.TASK, false));
        assertEquals(2, UnlockPointLedger.weight(AdvancementType.GOAL, false));
        assertEquals(3, UnlockPointLedger.weight(AdvancementType.CHALLENGE, false));
        assertEquals(4, UnlockPointLedger.weight(AdvancementType.CHALLENGE, true));
    }

    @Test
    @DisplayName("balance never goes negative after a revoke")
    void availableClamps() {
        assertEquals(7, UnlockPointLedger.available(12, 5));
        assertEquals(0, UnlockPointLedger.available(3, 5));
    }
}
