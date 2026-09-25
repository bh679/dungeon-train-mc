package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One band <em>occurrence</em> of the world-gen cycle, grouped by the {@link Lap} it belongs to — the
 * unit a template's spawn gate is written in. Unlike {@link TrainPhase} (which says what terrain a
 * column shows, so the vanilla Nether and BetterNether are both {@code NETHER}), each occurrence here
 * is its own value: a template can spawn in the vanilla-lap Nether but not the modded one.
 *
 * <p>Declaration order is the cycle order of {@link CycleLayout#DEFAULT_ORDER}, lap by lap; ordinals
 * are the {@link #bit()} wire mask, so appending is safe but reordering needs a protocol bump.
 * Tokens are persisted, so never rename one.</p>
 *
 * <p>{@link #forSlot} classifies a slot of any {@link CycleLayout} (pure, unit-tested);
 * {@code LapBandLocator} wraps it for a live world.</p>
 */
public enum LapBand {
    V_OVERWORLD_1(Lap.VANILLA, "O", "Overworld"),
    V_NETHER(Lap.VANILLA, "N", "Nether"),
    V_OVERWORLD_2(Lap.VANILLA, "O", "Overworld"),
    V_END(Lap.VANILLA, "E", "End"),
    V_UPSIDE_DOWN(Lap.VANILLA, "U", "Upside Down"),
    V_REASSEMBLY(Lap.VANILLA, "R", "Reassembly"),

    M_OVERWORLD_1(Lap.MOD, "O", "Overworld (WWOO)"),
    M_NETHER(Lap.MOD, "N", "BetterNether"),
    M_OVERWORLD_2(Lap.MOD, "O", "Overworld (BoP)"),
    M_END(Lap.MOD, "E", "BetterEnd"),
    M_SPHERES(Lap.MOD, "S", "Spheres"),
    M_OVERWORLD_3(Lap.MOD, "O", "Overworld"),

    L_LARGE_BIOMES(Lap.LEGACY, "L", "Large Biomes"),
    L_AMPLIFIED(Lap.LEGACY, "A", "Amplified"),
    L_BETA(Lap.LEGACY, "B", "Beta"),
    L_FAR_LANDS(Lap.LEGACY, "F", "Far Lands"),
    L_CAVES_OF_CHAOS(Lap.LEGACY, "C", "Caves of Chaos"),
    L_SKYLANDS(Lap.LEGACY, "S", "Skylands"),
    L_FLOATING(Lap.LEGACY, "F", "Floating"),
    L_ALPHA(Lap.LEGACY, "A", "Alpha"),
    L_INFDEV(Lap.LEGACY, "I", "Infdev"),
    L_CLASSIC(Lap.LEGACY, "C", "Classic"),
    L_SUPERFLAT(Lap.LEGACY, "S", "Superflat"),
    L_VOID(Lap.LEGACY, "V", "Void"),

    C_OVERWORLD_1(Lap.CORRUPT, "O", "Overworld"),
    C_CHUNCKS(Lap.CORRUPT, "C", "Chuncks"),
    C_OVERWORLD_2(Lap.CORRUPT, "O", "Overworld"),
    C_STACKS(Lap.CORRUPT, "S", "Stacks");

    /** The four laps of one run of the cycle, in cycle order. */
    public enum Lap {
        VANILLA("V", "Vanilla"),
        MOD("M", "Mod"),
        LEGACY("L", "Legacy"),
        CORRUPT("C", "Corrupt");

        private final String letter;
        private final String displayName;

        Lap(String letter, String displayName) {
            this.letter = letter;
            this.displayName = displayName;
        }

        public String letter() {
            return letter;
        }

        /** English name; the editor looks up {@code editor_menu.lap.<token>} first. */
        public String displayName() {
            return displayName;
        }

        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** This lap's bands in cycle order. */
        public List<LapBand> members() {
            return Arrays.stream(LapBand.values()).filter(b -> b.lap == this).toList();
        }

        /** {@link LapBand#bit()} mask of this lap's bands. */
        public int mask() {
            int m = 0;
            for (LapBand b : LapBand.values()) {
                if (b.lap == this) m |= b.bit();
            }
            return m;
        }
    }

    /** Every band's bit set — the "all bands" wire value. */
    public static final int ALL_MASK = (1 << values().length) - 1;

    /** Plain-overworld gaps of a run by ordinal (WWOO/BoP-styled gaps are counted separately). */
    private static final LapBand[] PLAIN_OVERWORLDS =
        {V_OVERWORLD_1, V_OVERWORLD_2, M_OVERWORLD_3, C_OVERWORLD_1, C_OVERWORLD_2};

    private final Lap lap;
    private final String letter;
    private final String displayName;

    LapBand(Lap lap, String letter, String displayName) {
        this.lap = lap;
        this.letter = letter;
        this.displayName = displayName;
    }

    public Lap lap() {
        return lap;
    }

    /** One-letter label within its lap (letters repeat across and within laps; position disambiguates). */
    public String letter() {
        return letter;
    }

    /** English name; the editor looks up {@code editor_menu.band.<token>} first. */
    public String displayName() {
        return displayName;
    }

    /** Persisted + command token ({@code v_nether}, {@code m_overworld_2}, …). */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public int bit() {
        return 1 << ordinal();
    }

    /** Position of this band within its lap (0-based). */
    public int indexInLap() {
        return lap.members().indexOf(this);
    }

    public static int toMask(Set<LapBand> bands) {
        int mask = 0;
        for (LapBand b : bands) mask |= b.bit();
        return mask;
    }

    public static EnumSet<LapBand> fromMask(int mask) {
        EnumSet<LapBand> set = EnumSet.noneOf(LapBand.class);
        for (LapBand b : values()) {
            if ((mask & b.bit()) != 0) set.add(b);
        }
        return set;
    }

    /** The band with this exact token, or null. */
    public static LapBand byToken(String token) {
        if (token == null) return null;
        String t = token.trim().toLowerCase(Locale.ROOT);
        for (LapBand b : values()) {
            if (b.token().equals(t)) return b;
        }
        return null;
    }

    /**
     * Every band a token names: a {@link LapBand} token is itself; an old {@link TrainPhase} token or
     * alias ({@code nether}, {@code ow}, …) expands through {@link #fromLegacy}. Empty when unknown.
     * This is what keeps pre-lap saves, relay uploads and typed commands working.
     */
    public static EnumSet<LapBand> resolve(String token) {
        LapBand b = byToken(token);
        if (b != null) return EnumSet.of(b);
        TrainPhase legacy = TrainPhase.byToken(token);
        return legacy == null ? EnumSet.noneOf(LapBand.class) : fromLegacy(legacy);
    }

    /**
     * The bands an old 18-value {@link TrainPhase} gate entry covered. Old {@code phaseAt} read fades,
     * the Upside Down's Reassembly and Superflat as {@code OVERWORLD}, so OVERWORLD takes those too.
     * {@code VOID} maps to the legacy void era only: the void around the End is now part of the End
     * band, and widening old overworld-flavoured gates (which listed VOID to carry on through the
     * End's disintegration wings) onto the whole End would let overworld stages claim the End.
     */
    public static EnumSet<LapBand> fromLegacy(TrainPhase phase) {
        return switch (phase) {
            case OVERWORLD -> EnumSet.of(V_OVERWORLD_1, V_OVERWORLD_2, V_REASSEMBLY, M_OVERWORLD_1,
                M_OVERWORLD_2, M_OVERWORLD_3, L_SUPERFLAT, C_OVERWORLD_1, C_OVERWORLD_2);
            case NETHER -> EnumSet.of(V_NETHER, M_NETHER);
            case END -> EnumSet.of(V_END, M_END);
            case VOID -> EnumSet.of(L_VOID);
            case UPSIDE_DOWN -> EnumSet.of(V_UPSIDE_DOWN);
            case CHUNCKS -> EnumSet.of(C_CHUNCKS);
            case SPHERES -> EnumSet.of(M_SPHERES);
            case STACKS -> EnumSet.of(C_STACKS);
            case BETA -> EnumSet.of(L_BETA);
            case ALPHA -> EnumSet.of(L_ALPHA);
            case SKYLANDS -> EnumSet.of(L_SKYLANDS);
            case INFDEV -> EnumSet.of(L_INFDEV);
            case FLOATING -> EnumSet.of(L_FLOATING);
            case FAR_LANDS -> EnumSet.of(L_FAR_LANDS);
            case CLASSIC -> EnumSet.of(L_CLASSIC);
            case CAVES_OF_CHAOS -> EnumSet.of(L_CAVES_OF_CHAOS);
            case LARGE_BIOMES -> EnumSet.of(L_LARGE_BIOMES);
            case AMPLIFIED -> EnumSet.of(L_AMPLIFIED);
        };
    }

    /** The single band a classic-mode {@link TrainPhase} column falls back to (first vanilla occurrence). */
    public static LapBand fallbackOf(TrainPhase phase) {
        return switch (phase) {
            case OVERWORLD -> V_OVERWORLD_1;
            case NETHER -> V_NETHER;
            case END, VOID -> V_END;
            case UPSIDE_DOWN -> V_UPSIDE_DOWN;
            default -> fromLegacy(phase).iterator().next();
        };
    }

    /** The legacy-lap band of a legacy era. */
    public static LapBand ofLegacyKind(LegacyBandKind kind) {
        return switch (kind) {
            case LARGE_BIOMES -> L_LARGE_BIOMES;
            case AMPLIFIED -> L_AMPLIFIED;
            case BETA -> L_BETA;
            case FAR_LANDS -> L_FAR_LANDS;
            case CAVES_OF_CHAOS -> L_CAVES_OF_CHAOS;
            case SKYLANDS -> L_SKYLANDS;
            case ALPHA -> L_ALPHA;
            case INFDEV -> L_INFDEV;
            case FLOATING -> L_FLOATING;
            case CLASSIC -> L_CLASSIC;
            case SUPERFLAT -> L_SUPERFLAT;
            case VOID -> L_VOID;
        };
    }

    // ---------- slot classification (pure) ----------

    /**
     * The band at {@code offset} blocks into slot {@code i} of {@code layout}. A slot's whole span —
     * fades included — belongs to it, except the Nether's overworld-looking rise (beach + mountains
     * either side of the netherrack), which stays with the neighbouring overworld gap as it always has;
     * the Upside Down splits into the band proper and its Reassembly (exit fade + exit gap); the legacy
     * run splits between eras at the middle of each crossfade.
     */
    public static LapBand forSlot(CycleLayout layout, int i, long offset) {
        CycleLayout.Slot s = layout.slot(i);
        CycleLayout.Fades f = layout.fades();
        return switch (s.type()) {
            case OVERWORLD -> overworldOf(layout, i);
            case NETHER -> {
                long rim = (long) Math.max(0, f.riseLen()) + Math.max(0, f.megaHold());
                if (offset < rim) yield neighbourOverworld(layout, i - 1, s);
                if (offset >= layout.length(i) - rim) yield neighbourOverworld(layout, i + 1, s);
                yield s.style() == CycleLayout.Style.BETTER ? M_NETHER : V_NETHER;
            }
            case END -> s.style() == CycleLayout.Style.BETTER ? M_END : V_END;
            case UPSIDE_DOWN -> offset < 2L * Math.max(0, f.udFade()) + Math.max(0, s.core())
                ? V_UPSIDE_DOWN : V_REASSEMBLY;
            case SPHERES -> M_SPHERES;
            case CHUNCKS -> C_CHUNCKS;
            case STACKS -> C_STACKS;
            case LEGACY_RUN -> eraAt(layout, offset);
        };
    }

    /**
     * The overworld gap beside the Nether at slot {@code i} — the entry side before its middle, the exit
     * side after — for the Overworld↔Nether block crossfade. Outside a Nether slot, the slot's own band.
     */
    public static LapBand overworldBesideNether(CycleLayout layout, int i, long offset) {
        CycleLayout.Slot s = layout.slot(i);
        if (s.type() != CycleLayout.Type.NETHER) return forSlot(layout, i, offset);
        return neighbourOverworld(layout, offset < layout.length(i) / 2 ? i - 1 : i + 1, s);
    }

    private static LapBand eraAt(CycleLayout layout, long offset) {
        LegacySpan[] eras = layout.eras();
        if (eras.length == 0) return L_VOID;
        long halfFade = layout.legacyFade() / 2;
        for (int e = 0; e < eras.length - 1; e++) {
            if (offset < layout.eraCoreStart(e) + layout.eraCoreLen(e) + halfFade) {
                return ofLegacyKind(eras[e].kind());
            }
        }
        return ofLegacyKind(eras[eras.length - 1].kind());
    }

    /** Overworld gap {@code i}: WWOO / BoP by style, plain gaps by their ordinal among the run's plain gaps. */
    private static LapBand overworldOf(CycleLayout layout, int i) {
        CycleLayout.Style style = layout.slot(i).style();
        if (style == CycleLayout.Style.WWOO) return M_OVERWORLD_1;
        if (style == CycleLayout.Style.BOP) return M_OVERWORLD_2;
        int n = 0;
        for (int j = 0; j < i; j++) {
            CycleLayout.Slot o = layout.slot(j);
            if (o.type() == CycleLayout.Type.OVERWORLD && o.style() == CycleLayout.Style.VANILLA) n++;
        }
        if (n < PLAIN_OVERWORLDS.length) return PLAIN_OVERWORLDS[n];
        // A custom order with extra gaps: the last overworld of the lap the gap follows.
        for (int j = i - 1; j >= 0; j--) {
            if (layout.slot(j).type() == CycleLayout.Type.OVERWORLD) continue;
            return switch (forSlot(layout, j, 0L).lap()) {
                case VANILLA -> V_OVERWORLD_2;
                case MOD -> M_OVERWORLD_3;
                case LEGACY, CORRUPT -> C_OVERWORLD_2;
            };
        }
        return V_OVERWORLD_1;
    }

    /** The overworld gap at slot {@code j} beside a Nether, or that Nether itself when there is none. */
    private static LapBand neighbourOverworld(CycleLayout layout, int j, CycleLayout.Slot nether) {
        if (j >= 0 && j < layout.count() && layout.slot(j).type() == CycleLayout.Type.OVERWORLD) {
            return overworldOf(layout, j);
        }
        return nether.style() == CycleLayout.Style.BETTER ? M_NETHER : V_NETHER;
    }
}
