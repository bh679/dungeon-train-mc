package games.brennan.dungeontrain.worldgen;

import java.util.Arrays;
import java.util.List;

/**
 * The four families the editor groups {@link TrainPhase bands} into, so a template's band gate reads
 * as four tri-state toggles instead of one letter per band. Membership lives on
 * {@link TrainPhase#group()} (an exhaustive switch — a new band must pick a family to compile);
 * everything here is derived from it.
 */
public enum BandGroup {
    /** The cycle's own dimensions: Overworld, Nether, Void, End, Upside Down. */
    CORE,
    /** Mostly-void bands of scattered terrain: Chuncks, Spheres, Stacks. */
    FRAGMENTS,
    /** Ports of old Minecraft generators: Classic, Indev Floating, Infdev, Alpha, Beta, Far Lands, Skylands, Caves of Chaos. */
    LEGACY,
    /** Vanilla world presets: Large Biomes, Amplified. */
    PRESETS;

    /** How much of a group a band mask covers. */
    public enum State { ALL, SOME, NONE }

    /** The bands in this group, in {@link TrainPhase} declaration order. */
    public List<TrainPhase> members() {
        return Arrays.stream(TrainPhase.values()).filter(p -> p.group() == this).toList();
    }

    /** Bitmask of this group's bands ({@link TrainPhase#bit()} per member). */
    public int mask() {
        int m = 0;
        for (TrainPhase p : TrainPhase.values()) {
            if (p.group() == this) m |= p.bit();
        }
        return m;
    }

    /** Whether {@code phaseMask} covers all, some or none of this group. */
    public State state(int phaseMask) {
        int covered = phaseMask & mask();
        if (covered == 0) return State.NONE;
        return covered == mask() ? State.ALL : State.SOME;
    }

    /** Lower-cased token, used for lang keys ({@code core}, {@code fragments}, …). */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
