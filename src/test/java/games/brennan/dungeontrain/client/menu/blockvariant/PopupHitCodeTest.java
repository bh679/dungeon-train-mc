package games.brennan.dungeontrain.client.menu.blockvariant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Z menu popups' hit code ({@link GrowthPopupLayout#encode}). A Group-mode If strip has one slot
 * per block the group can roll, so a slot past 9 must not spill into the next section — slot 17 of
 * the block strip used to decode as the action row and clear the condition.
 */
class PopupHitCodeTest {

    @Test
    void sectionAndOptionRoundTrip() {
        for (int section = 0; section <= ConditionPopupLayout.SECTION_KIND; section++) {
            for (int option = 0; option <= 50; option++) {
                int code = GrowthPopupLayout.encode(section, option);
                assertEquals(section, GrowthPopupLayout.section(code));
                assertEquals(option, GrowthPopupLayout.option(code));
            }
        }
    }

    @Test
    void lateStripSlotStaysInTheBlockStrip() {
        int code = GrowthPopupLayout.encode(ConditionPopupLayout.SECTION_BLOCKS, 17);
        assertEquals(ConditionPopupLayout.SECTION_BLOCKS, GrowthPopupLayout.section(code));
        assertEquals(17, GrowthPopupLayout.option(code));
    }

    @Test
    void spanPopupSharesTheEncoding() {
        assertEquals(GrowthPopupLayout.encode(2, 13), SpanPopupLayout.encode(2, 13));
    }
}
