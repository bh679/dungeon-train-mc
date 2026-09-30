package games.brennan.dungeontrain.track;

import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The band a Tracks-category Test the Carriage stands in — one the template under test could really
 * appear in. The phase is drawn at random from its gate's phases (every phase for an ungated one),
 * and the Diff-Level is the gate's lowest, the first level it appears at, so the level can never be
 * what rules it out. Everything around the piece — the rest of the line, the carriage, its
 * contents — is then picked through the band, as the world would pick it there.
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public record TrackTestBand(int level, TrainPhase phase) {

    /** Keeps the band's draw apart from every other roll made on the same seed. */
    private static final long SALT = 0x5DEECE66DL;

    /** A band the {@code gate} admits, drawn on {@code seed}. */
    public static TrackTestBand pick(TemplateGate gate, long seed) {
        List<TrainPhase> phases = new ArrayList<>(gate.phases());
        // In declaration order, so the draw depends on the seed alone and not on the set's iteration.
        phases.sort(Comparator.comparingInt(Enum::ordinal));
        // SplittableRandom, not Random: Random's first draw barely moves between neighbouring seeds,
        // so a reseed would keep landing in the same band.
        TrainPhase phase = phases.get(new SplittableRandom(seed ^ SALT).nextInt(phases.size()));
        return new TrackTestBand(gate.minLevel(), phase);
    }

    /** The band as the context the weighted pickers filter on. */
    public GateContext context() {
        return new GateContext(level, phase);
    }
}
