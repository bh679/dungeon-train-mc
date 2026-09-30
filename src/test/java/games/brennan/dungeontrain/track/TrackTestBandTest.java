package games.brennan.dungeontrain.track;

import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins that a track test always stands in a band the template under test could appear in. */
final class TrackTestBandTest {

    @Test
    @DisplayName("The band is always one of the gate's phases, at the gate's first level")
    void withinTheGate() {
        TemplateGate gate = new TemplateGate(12, 40, EnumSet.of(TrainPhase.NETHER, TrainPhase.END));
        Set<TrainPhase> seen = new HashSet<>();
        for (long seed = 0; seed < 200; seed++) {
            TrackTestBand band = TrackTestBand.pick(gate, seed);
            assertTrue(gate.phases().contains(band.phase()), band.toString());
            assertEquals(12, band.level());
            assertTrue(gate.eligible(band.level(), band.phase()));
            seen.add(band.phase());
        }
        // Random across the template's options, not stuck on one.
        assertEquals(gate.phases(), seen);
    }

    @Test
    @DisplayName("An ungated template can stand in any band")
    void ungatedRollsAnyBand() {
        Set<TrainPhase> seen = new HashSet<>();
        for (long seed = 0; seed < 2000; seed++) seen.add(TrackTestBand.pick(TemplateGate.DEFAULT, seed).phase());
        assertEquals(TemplateGate.ALL_PHASES, seen);
    }

    @Test
    @DisplayName("The same seed draws the same band")
    void deterministic() {
        TemplateGate gate = new TemplateGate(0, TemplateGate.ALL,
            EnumSet.of(TrainPhase.OVERWORLD, TrainPhase.NETHER, TrainPhase.VOID));
        for (long seed = -5; seed < 5; seed++) {
            assertEquals(TrackTestBand.pick(gate, seed), TrackTestBand.pick(gate, seed));
        }
    }
}
