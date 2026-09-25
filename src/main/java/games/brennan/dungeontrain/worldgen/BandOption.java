package games.brennan.dungeontrain.worldgen;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import static games.brennan.dungeontrain.worldgen.LapBand.*;

/**
 * The editor's band <em>options</em>: what a template's band gate is edited in. Gates are still stored
 * per {@link LapBand} occurrence; each option simply owns a fixed set of those bands and turns them
 * on or off together — so vanilla and modded occurrences share one option today, and splitting them
 * back out later is a change here, not a data migration.
 *
 * <p>The options partition every {@link LapBand} exactly once (enforced by {@code BandOptionTest}),
 * so a new band has to join an option. Declaration order is the row order.</p>
 */
public enum BandOption {
    OVERWORLD(Group.MAIN, "O", "Overworld",
        V_OVERWORLD_1, V_OVERWORLD_2, M_OVERWORLD_1, M_OVERWORLD_2, M_OVERWORLD_3, C_OVERWORLD_1, C_OVERWORLD_2),
    NETHER(Group.MAIN, "N", "Nether", V_NETHER, M_NETHER),
    END(Group.MAIN, "E", "End", V_END, M_END),
    CUSTOM(Group.MAIN, "C", "Custom", V_UPSIDE_DOWN, V_REASSEMBLY, M_SPHERES),

    PRE_FAR_LANDS(Group.LEGACY, "P", "Pre Far Lands", L_LARGE_BIOMES, L_AMPLIFIED, L_BETA),
    FAR_LANDS(Group.LEGACY, "F", "Far Lands", L_FAR_LANDS, L_CAVES_OF_CHAOS, L_SKYLANDS, L_FLOATING),
    OLD(Group.LEGACY, "O", "Old", L_ALPHA, L_INFDEV, L_CLASSIC, L_SUPERFLAT, L_VOID),

    CHUNCKS(Group.CORRUPT, "C", "Chuncks", C_CHUNCKS),
    STACKS(Group.CORRUPT, "S", "Stacks", C_STACKS);

    /** The option groups, in row order. */
    public enum Group {
        MAIN("Main"),
        LEGACY("Legacy"),
        CORRUPT("Corrupt");

        private final String displayName;

        Group(String displayName) {
            this.displayName = displayName;
        }

        /** English name; the editor looks up {@code editor_menu.band_group.<token>} first. */
        public String displayName() {
            return displayName;
        }

        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        public List<BandOption> options() {
            return Arrays.stream(BandOption.values()).filter(o -> o.group == this).toList();
        }

        /** {@link LapBand#bit()} mask of every band in this group's options. */
        public int mask() {
            int m = 0;
            for (BandOption o : options()) m |= o.mask;
            return m;
        }
    }

    /** How much of an option a band mask covers. */
    public enum State { ALL, SOME, NONE }

    private final Group group;
    private final String letter;
    private final String displayName;
    private final EnumSet<LapBand> bands;
    private final int mask;

    BandOption(Group group, String letter, String displayName, LapBand... bands) {
        this.group = group;
        this.letter = letter;
        this.displayName = displayName;
        this.bands = EnumSet.copyOf(Arrays.asList(bands));
        this.mask = LapBand.toMask(this.bands);
    }

    public Group group() {
        return group;
    }

    /** Row label (letters repeat across groups; position and hover disambiguate). */
    public String letter() {
        return letter;
    }

    /** English name; the editor looks up {@code editor_menu.band_option.<token>} first. */
    public String displayName() {
        return displayName;
    }

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The bands this option turns on and off (a copy). */
    public EnumSet<LapBand> bands() {
        return EnumSet.copyOf(bands);
    }

    /** {@link LapBand#bit()} mask of this option's bands. */
    public int mask() {
        return mask;
    }

    /** Whether {@code bandMask} covers all, some or none of this option. */
    public State state(int bandMask) {
        int covered = bandMask & mask;
        if (covered == 0) return State.NONE;
        return covered == mask ? State.ALL : State.SOME;
    }
}
