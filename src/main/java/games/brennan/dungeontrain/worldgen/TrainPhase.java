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

    /**
     * Salt for the Overworld↔Nether <em>gate</em> dither — a distinct stream from
     * {@code NetherTransitionFeature}'s {@code CROSSFADE_DITHER_SALT}, but fed through the same
     * {@link Disintegration#coherentNoise} (cells 8/3), so the gate fade clumps at the same scale
     * and organic character as the ground's stone→netherrack crossfade.
     */
    private static final long NETHER_GATE_FADE_SALT = 0x4F1BBCDCBFA53E0BL;

    /** Fixed Y sample for the gate dither — the noise then varies as 1-D coherent noise along world-X. */
    private static final int GATE_FADE_NOISE_Y = 0;

    /**
     * Pure, unit-testable Overworld↔Nether fade decision for <b>template gating</b>. Outside the
     * Nether crossfade ({@code netherRamp} 0 or 1) it returns {@code base} unchanged — byte-identical
     * to {@link #phaseAt} — so only the crossfade zone is affected and non-Nether phases
     * ({@link #VOID}/{@link #END}) are never touched. Inside the crossfade it dithers between
     * {@link #OVERWORLD} and {@link #NETHER} with {@code P(NETHER) = netherRamp}, using the same
     * {@code noise < ramp} convention as the terrain's stone→netherrack recolour so structures clump
     * Nether where the ground clumps netherrack. Because the average crossover is at
     * {@code ramp == 0.5}, the core boundary is unchanged — the hard line just softens into a dither
     * centred on the same spot.
     */
    static TrainPhase fadeNetherGate(TrainPhase base, double netherRamp, double noise) {
        if (base != OVERWORLD && base != NETHER) return base;     // VOID / END untouched
        if (netherRamp <= 0.0 || netherRamp >= 1.0) return base;  // outside the crossfade — hard
        return noise < netherRamp ? NETHER : OVERWORLD;           // dither, in lock-step with terrain
    }

    /**
     * Gate phase at {@code worldX} with the Nether crossfade <b>noise-faded</b> — used by the
     * world-feature gate ({@link games.brennan.dungeontrain.template.GateContext#atWorldX}: tunnels,
     * tracks, pillars) so they dither between their Overworld and Nether-dark variants across the
     * crossfade instead of snapping at a single line. {@code genSeed} is the per-world
     * {@code generationSeed} (the same seed the terrain recolour uses), so the dither is deterministic
     * and reproducible across reloads / rolling-window re-renders. Carriage gating keeps the hard
     * {@link #phaseAt} classification (see {@code GateContext#forCarriage}); {@link #phaseAt} itself —
     * and every gameplay reader of it (mob spawning, piglin zombification, client sky) — is unchanged.
     */
    public static TrainPhase gatePhaseAt(ServerLevel overworld, int worldX, long genSeed) {
        TrainPhase base = phaseAt(overworld, worldX);
        double ramp = NetherBand.netherRampAt(overworld, worldX);
        double noise = Disintegration.coherentNoise(
            genSeed ^ NETHER_GATE_FADE_SALT, worldX, GATE_FADE_NOISE_Y, 0);
        return fadeNetherGate(base, ramp, noise);
    }
}
