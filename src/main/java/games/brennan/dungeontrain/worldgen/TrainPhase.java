package games.brennan.dungeontrain.worldgen;

import net.minecraft.server.level.ServerLevel;

import java.util.EnumSet;
import java.util.Set;

/**
 * The four worldgen phases a column of the repeating {@link WorldGenCycle} can sit in, as a
 * single 4-value classification — unlike {@link Disintegration.Zone} (3 values, Nether-less).
 * Used by the per-template spawn gate
 * ({@link games.brennan.dungeontrain.template.TemplateGate}): a weighted template may restrict
 * itself to a subset of phases, and the generator filters the candidate pool by the phase of the
 * column it is being placed in.
 *
 * <p>The cycle runs OW → Nether → OW → Void → End → Void → … along +X.
 * {@link #phaseAt(ServerLevel, int)} classifies a world-X by combining the two existing
 * server-side classifiers with the same precedence
 * {@link games.brennan.dungeontrain.event.ZoneProgressEvents} uses: the Nether core is tested
 * first (it reads {@code middleRamp == 0}, so {@link DisintegrationBand#zoneAt} would otherwise
 * mis-classify it as {@link #OVERWORLD}), then the void/End classification. When the bands are
 * disabled (or the world has no train) the column degrades to {@link #OVERWORLD}, the safe
 * default.</p>
 */
public enum TrainPhase {
    OVERWORLD,
    NETHER,
    VOID,
    END;

    /** Bitmask with every phase set ({@code 1<<ordinal} per value) — the "all phases" wire value. */
    public static final int ALL_MASK = (1 << values().length) - 1;

    /** This phase's single-bit mask ({@code 1 << ordinal()}). */
    public int bit() {
        return 1 << ordinal();
    }

    /** Pack a phase set into a {@link #bit()} bitmask. */
    public static int toMask(Set<TrainPhase> phases) {
        int mask = 0;
        for (TrainPhase p : phases) mask |= p.bit();
        return mask;
    }

    /** Unpack a {@link #toMask} bitmask back into an {@link EnumSet}. */
    public static EnumSet<TrainPhase> fromMask(int mask) {
        EnumSet<TrainPhase> set = EnumSet.noneOf(TrainPhase.class);
        for (TrainPhase p : values()) {
            if ((mask & p.bit()) != 0) set.add(p);
        }
        return set;
    }

    /** Lower-cased command token for this phase ({@code overworld}, {@code nether}, …). */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Parse a command token ({@code ow}/{@code overworld}/{@code nether}/{@code void}/{@code end}); null if unknown. */
    public static TrainPhase byToken(String token) {
        if (token == null) return null;
        String t = token.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.equals("ow")) return OVERWORLD;
        for (TrainPhase p : values()) {
            if (p.token().equals(t)) return p;
        }
        return null;
    }

    /**
     * Which phase the column at {@code worldX} sits in for this {@code overworld}. A pure read of
     * the existing band helpers ({@link NetherBand#isInNetherBiome},
     * {@link DisintegrationBand#zoneAt}) — never mutates worldgen state — so it is safe to call at
     * carriage-selection time. Returns {@link #OVERWORLD} when the bands are off.
     */
    public static TrainPhase phaseAt(ServerLevel overworld, int worldX) {
        if (NetherBand.isInNetherBiome(overworld, worldX)) {
            return NETHER;
        }
        return switch (DisintegrationBand.zoneAt(overworld, worldX)) {
            case VOID -> VOID;
            case END_ISLANDS -> END;
            case OVERWORLD -> OVERWORLD;
        };
    }
}
