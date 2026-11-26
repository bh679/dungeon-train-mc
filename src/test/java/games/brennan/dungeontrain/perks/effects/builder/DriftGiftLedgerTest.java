package games.brennan.dungeontrain.perks.effects.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fraction ledger: cheap gifts add up, take-backs spend the ledger before any emerald. */
class DriftGiftLedgerTest {

    @Test
    void creditPaysWholeEmeraldsAndKeepsTheChange() {
        DriftGiftLedger.Move m = DriftGiftLedger.credit(0, 22);
        assertEquals(1, m.emeralds());
        assertEquals(6, m.balance());
    }

    @Test
    void smallCreditsAccumulateIntoAnEmerald() {
        int balance = 0;
        int paid = 0;
        for (int i = 0; i < 4; i++) {
            DriftGiftLedger.Move m = DriftGiftLedger.credit(balance, 5);
            paid += m.emeralds();
            balance = m.balance();
        }
        assertEquals(1, paid);
        assertEquals(4, balance);
    }

    @Test
    void chargeSpendsTheBalanceFirst() {
        DriftGiftLedger.Move m = DriftGiftLedger.charge(10, 6);
        assertEquals(0, m.emeralds());
        assertEquals(4, m.balance());
    }

    @Test
    void chargeBeyondTheBalanceTakesEmeraldsAndKeepsChange() {
        DriftGiftLedger.Move m = DriftGiftLedger.charge(4, 22);
        // shortfall 18 -> 2 emeralds (32), change 14
        assertEquals(2, m.emeralds());
        assertEquals(14, m.balance());
    }

    @Test
    void canChargeFollowsTheEmeraldsHeld() {
        assertTrue(DriftGiftLedger.canCharge(0, 22, 2));
        assertFalse(DriftGiftLedger.canCharge(0, 22, 1));
        assertTrue(DriftGiftLedger.canCharge(6, 22, 1), "6 banked + 16 from one emerald covers 22");
        assertTrue(DriftGiftLedger.canCharge(0, 0, 0));
    }

    @Test
    void creditThenChargeIsANoOpOnTheWhole() {
        DriftGiftLedger.Move paid = DriftGiftLedger.credit(0, 22);
        DriftGiftLedger.Move back = DriftGiftLedger.charge(paid.balance(), 22);
        assertEquals(paid.emeralds(), back.emeralds());
        assertEquals(0, back.balance());
    }
}
