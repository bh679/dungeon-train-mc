package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static games.brennan.dungeontrain.worldgen.LapBand.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link LapBand#forSlot} over the shipped {@link CycleLayout#DEFAULT_ORDER}. */
final class LapBandSlotMapTest {

    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();

    private static LapBand mid(int slot) {
        return LapBand.forSlot(LAYOUT, slot, LAYOUT.length(slot) / 2);
    }

    @Test
    @DisplayName("slot midpoints read V: O N O E U, M: O N O E S O, legacy, C: O C O S")
    void slotMidpoints() {
        List<LapBand> got = new ArrayList<>();
        for (int i = 0; i < LAYOUT.count(); i++) got.add(mid(i));
        // The Upside Down's long Reassembly holds the slot midpoint; sample its band core instead.
        got.set(4, forSlot(LAYOUT, 4, LAYOUT.fades().udFade() + LAYOUT.slot(4).core() / 2));
        assertEquals(List.of(
            V_OVERWORLD_1, V_NETHER, V_OVERWORLD_2, V_END, V_UPSIDE_DOWN,
            M_OVERWORLD_1, M_NETHER, M_OVERWORLD_2, M_END, M_SPHERES, M_OVERWORLD_3,
            forSlot(LAYOUT, 11, LAYOUT.length(11) / 2),
            C_OVERWORLD_1, C_CHUNCKS, C_OVERWORLD_2, C_STACKS), got);
    }

    @Test
    @DisplayName("the Upside Down slot splits into the band and its Reassembly")
    void reassembly() {
        int ud = 4;
        CycleLayout.Fades f = LAYOUT.fades();
        long bandEnd = 2L * f.udFade() + LAYOUT.slot(ud).core();
        assertEquals(V_UPSIDE_DOWN, forSlot(LAYOUT, ud, bandEnd - 1));
        assertEquals(V_REASSEMBLY, forSlot(LAYOUT, ud, bandEnd));
        assertEquals(V_REASSEMBLY, forSlot(LAYOUT, ud, LAYOUT.length(ud) - 1));
    }

    @Test
    @DisplayName("the Nether's overworld-looking rise stays with the gap beside it")
    void netherRise() {
        long rim = LAYOUT.fades().riseLen() + LAYOUT.fades().megaHold();
        assertEquals(V_OVERWORLD_1, forSlot(LAYOUT, 1, 0));
        assertEquals(V_NETHER, forSlot(LAYOUT, 1, rim));
        assertEquals(V_OVERWORLD_2, forSlot(LAYOUT, 1, LAYOUT.length(1) - 1));
        assertEquals(M_OVERWORLD_1, forSlot(LAYOUT, 6, 0));
        assertEquals(M_OVERWORLD_2, forSlot(LAYOUT, 6, LAYOUT.length(6) - 1));
        assertEquals(V_OVERWORLD_1, overworldBesideNether(LAYOUT, 1, rim + 10));
        assertEquals(V_OVERWORLD_2, overworldBesideNether(LAYOUT, 1, LAYOUT.length(1) - rim - 10));
    }

    @Test
    @DisplayName("the legacy run reads each era in order, split at the crossfade midpoints")
    void legacyEras() {
        int legacy = 11;
        List<LapBand> got = new ArrayList<>();
        for (int e = 0; e < LAYOUT.eras().length; e++) {
            got.add(forSlot(LAYOUT, legacy, LAYOUT.eraCoreStart(e) + LAYOUT.eraCoreLen(e) / 2));
        }
        List<LapBand> want = new ArrayList<>();
        for (var span : LAYOUT.eras()) want.add(ofLegacyKind(span.kind()));
        assertEquals(want, got);
        assertEquals(L_AMPLIFIED, forSlot(LAYOUT, legacy, 0));
        long boundary = LAYOUT.eraCoreStart(0) + LAYOUT.eraCoreLen(0) + LAYOUT.legacyFade() / 2;
        assertEquals(L_AMPLIFIED, forSlot(LAYOUT, legacy, boundary - 1));
        assertEquals(L_BETA, forSlot(LAYOUT, legacy, boundary));
        assertEquals(L_VOID, forSlot(LAYOUT, legacy, LAYOUT.length(legacy) - 1));
    }
}
