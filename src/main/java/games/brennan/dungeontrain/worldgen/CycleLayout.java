package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The <b>ordered slot layout</b> of one run of the world-gen cycle: which bands come in which order, how
 * long each core is, and which style each occurrence wears. Pure (no Minecraft types) so it unit-tests
 * without a bootstrap, and immutable so one instance is shared across worldgen threads.
 *
 * <p>Each slot is a band <em>occurrence</em> — Nether and End appear twice per run (vanilla, then the
 * BetterNether / BetterEnd style), the overworld gaps are explicit slots (with the WWOO / BoP styles on
 * lap 2), and the legacy eras share one {@link Type#LEGACY_RUN} slot in which they crossfade straight
 * into each other. Slot lengths are {@code core + the band's own fades}, computed from the
 * {@link Fades} the cycle carries, so every band's existing ramp maths keeps working at a slot-local
 * offset. {@link #period()} is the length of run 0; later runs stretch — see {@link #runIndex} /
 * {@link #baseCoord}: run {@code k} is {@code 2^k} times as long and every ramp is evaluated at the
 * base coordinate {@code u} in {@code [0, period)}.</p>
 *
 * <p>Parsed from the {@code worldgenCycleOrder} COMMON key ({@link #parse}): comma-separated
 * {@code slot[:style][:length]} tokens — {@code ow}, {@code nether}, {@code end}, {@code upside_down}
 * ({@code :core:reassembly}), {@code chuncks}, {@code spheres}, {@code stacks}, and {@code legacy}
 * ({@code :kind=core} per era, run in the order written, or bare for every enabled era at its configured
 * length in declaration order). A band whose
 * config flag is off is dropped from the layout wherever the order names it.</p>
 */
public final class CycleLayout {

    /** The kinds of slot a run is made of. */
    public enum Type { OVERWORLD, NETHER, END, UPSIDE_DOWN, CHUNCKS, SPHERES, STACKS, LEGACY_RUN }

    /**
     * Which look an occurrence wears. A label the band's own code reads
     * ({@code WorldGenCycle#netherStyleAt} etc.); the layout itself is style-agnostic.
     */
    public enum Style { VANILLA, WWOO, BOP, BETTER }

    /**
     * One parsed slot. {@code core} is the band's full-strength length; {@code extra} is the
     * upside-down slot's Reassembly (exit-fade) length, or {@code -1} for the cycle's default.
     */
    public record Slot(Type type, Style style, int core, int extra) {}

    /**
     * The fade geometry slot lengths depend on — the same numbers the cycle record carries for the
     * classic layout. {@code riseLen} is the Nether's beach + mountain stages.
     */
    public record Fades(int riseLen, int megaHold, int coreFade, int eFade, int eVoid,
                        int udFade, int udExit, int udExitFade,
                        int chuncksFade, int spheresFade, int stacksFade, int legacyFade) {}

    /** The default three-lap order (see the plan): the layout {@code build()} uses when the key is blank. */
    public static final String DEFAULT_ORDER =
            "ow:2750, nether:3000, ow:3000, end:3000, upside_down:2500:6000, "
            + "ow:wwoo:8000, nether:better:8000, ow:bop:8000, end:better:8000, spheres:15000, ow:5000, "
            + "legacy:amplified=5000:beta=3500:far_lands=4320:caves_of_chaos=4000:skylands=5000:floating=2000:alpha=2000:infdev=2000:classic=2000:superflat=1000:void=200, "
            + "ow:2000, chuncks:5000, ow:5000, stacks:5000";

    private final Slot[] slots;
    private final long[] starts;
    private final long[] lens;
    private final int[] occurrence;
    private final LegacySpan[] eras;
    private final Fades fades;
    private final long period;
    private final Map<Type, Integer> typeCounts = new EnumMap<>(Type.class);

    private CycleLayout(Slot[] slots, LegacySpan[] eras, Fades fades) {
        this.slots = slots;
        this.eras = eras;
        this.fades = fades;
        this.starts = new long[slots.length];
        this.lens = new long[slots.length];
        this.occurrence = new int[slots.length];
        long at = 0L;
        for (int i = 0; i < slots.length; i++) {
            starts[i] = at;
            lens[i] = slotLength(slots[i]);
            occurrence[i] = typeCounts.merge(slots[i].type(), 1, Integer::sum) - 1;
            at += lens[i];
        }
        this.period = at;
    }

    // ---- construction ----------------------------------------------------------------

    /**
     * Parse an order spec. {@code legacyDefaults} are the enabled eras with their configured core lengths
     * (a bare {@code legacy} token takes them as they are; {@code kind=len} overrides a length);
     * {@code enabled} says which band types the config has switched on; {@code warn} receives one line per
     * ignored token. Returns {@code null} when the spec is blank or yields no slot at all.
     */
    public static CycleLayout parse(String spec, Fades fades, LegacySpan[] legacyDefaults,
                                    java.util.function.Predicate<Type> enabled, Consumer<String> warn) {
        if (spec == null || spec.isBlank()) return null;
        List<Slot> slots = new ArrayList<>();
        LegacySpan[] eras = new LegacySpan[0];
        for (String raw : spec.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            String[] parts = token.split(":");
            String name = parts[0].trim().toLowerCase(Locale.ROOT);
            Type type = typeOf(name);
            if (type == null) {
                warn.accept("unknown slot '" + token + "'");
                continue;
            }
            if (!enabled.test(type)) continue;
            if (type == Type.LEGACY_RUN) {
                eras = parseEras(parts, legacyDefaults, warn);
                if (eras.length == 0) continue;
                slots.add(new Slot(type, Style.VANILLA, 0, -1));
                continue;
            }
            Style style = Style.VANILLA;
            int core = -1;
            int extra = -1;
            for (int i = 1; i < parts.length; i++) {
                String arg = parts[i].trim().toLowerCase(Locale.ROOT);
                Style s = styleOf(arg);
                if (s != null) {
                    style = s;
                    continue;
                }
                try {
                    int n = Integer.parseInt(arg);
                    if (core < 0) core = n;
                    else extra = n;
                } catch (NumberFormatException e) {
                    warn.accept("ignored argument '" + arg + "' on '" + token + "'");
                }
            }
            if (core < 0) {
                warn.accept("slot '" + token + "' has no length; skipped");
                continue;
            }
            if (core == 0 && type != Type.OVERWORLD) continue;    // a zero-length band is just dropped
            slots.add(new Slot(type, style, core, extra));
        }
        if (slots.isEmpty()) return null;
        return new CycleLayout(slots.toArray(new Slot[0]), eras, fades);
    }

    /** Build directly from slots (tests). */
    public static CycleLayout of(List<Slot> slots, LegacySpan[] eras, Fades fades) {
        return new CycleLayout(slots.toArray(new Slot[0]), eras, fades);
    }

    /**
     * The legacy run's eras. Named eras run in the order written; a bare {@code legacy} takes every enabled
     * era in {@link LegacyBandKind} declaration order. An era named twice keeps its first position.
     */
    private static LegacySpan[] parseEras(String[] parts, LegacySpan[] defaults, Consumer<String> warn) {
        Map<LegacyBandKind, Integer> cores = new java.util.LinkedHashMap<>();
        boolean explicit = parts.length > 1;
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i].trim().toLowerCase(Locale.ROOT);
            int eq = arg.indexOf('=');
            String kindName = eq < 0 ? arg : arg.substring(0, eq);
            LegacyBandKind kind = kindOf(kindName);
            if (kind == null) {
                warn.accept("unknown legacy era '" + arg + "'");
                continue;
            }
            int len = -1;
            if (eq >= 0) {
                try {
                    len = Integer.parseInt(arg.substring(eq + 1));
                } catch (NumberFormatException e) {
                    warn.accept("bad length on legacy era '" + arg + "'");
                    continue;
                }
            }
            if (cores.containsKey(kind)) {
                warn.accept("legacy era '" + kindName + "' named twice; keeping the first");
                continue;
            }
            cores.put(kind, len);
        }
        Iterable<LegacyBandKind> order = explicit ? cores.keySet() : Arrays.asList(LegacyBandKind.values());
        List<LegacySpan> out = new ArrayList<>();
        for (LegacyBandKind kind : order) {
            LegacySpan d = defaultOf(defaults, kind);
            if (d == null || d.holdLen() <= 0L) continue;             // disabled in config
            Integer len = cores.get(kind);
            int core = len == null || len < 0 ? d.hold() : len;
            if (core <= 0) continue;
            out.add(new LegacySpan(kind, 0, d.fade(), core));
        }
        return out.toArray(new LegacySpan[0]);
    }

    private static LegacySpan defaultOf(LegacySpan[] defaults, LegacyBandKind kind) {
        if (defaults == null) return null;
        for (LegacySpan s : defaults) {
            if (s.kind() == kind) return s;
        }
        return null;
    }

    private static Type typeOf(String name) {
        return switch (name) {
            case "ow", "overworld" -> Type.OVERWORLD;
            case "nether" -> Type.NETHER;
            case "end" -> Type.END;
            case "ud", "upside_down", "upsidedown" -> Type.UPSIDE_DOWN;
            case "chuncks", "chunks" -> Type.CHUNCKS;
            case "spheres" -> Type.SPHERES;
            case "stacks" -> Type.STACKS;
            case "legacy" -> Type.LEGACY_RUN;
            default -> null;
        };
    }

    private static Style styleOf(String arg) {
        return switch (arg) {
            case "vanilla" -> Style.VANILLA;
            case "wwoo" -> Style.WWOO;
            case "bop" -> Style.BOP;
            case "better" -> Style.BETTER;
            default -> null;
        };
    }

    private static LegacyBandKind kindOf(String name) {
        for (LegacyBandKind k : LegacyBandKind.values()) {
            if (k.token().equals(name) || k.token().replace("_", "").equals(name)) return k;
        }
        return null;
    }

    /** Full length of a slot: its core plus the band's own fades. */
    private long slotLength(Slot s) {
        return switch (s.type()) {
            case OVERWORLD -> Math.max(0, s.core());
            case NETHER -> NetherTransition.bandLength(fades.riseLen(), fades.megaHold(), fades.coreFade(), s.core());
            case END -> Disintegration.bandLength(fades.eFade(), fades.eVoid(), s.core());
            case UPSIDE_DOWN -> 2L * Math.max(0, fades.udFade()) + s.core() + udReassembly(s) + Math.max(0, fades.udExit());
            case CHUNCKS -> Math.max(0, fades.chuncksFade()) + s.core();
            case SPHERES -> Math.max(0, fades.spheresFade()) + s.core();
            case STACKS -> Math.max(0, fades.stacksFade()) + s.core();
            case LEGACY_RUN -> legacyRunLength();
        };
    }

    /** The upside-down slot's Reassembly (exit crossfade) length — its own, or the cycle default. */
    public long udReassembly(Slot s) {
        return Math.max(0, s.extra() >= 0 ? s.extra() : fades.udExitFade());
    }

    /** {@code F + Σcore + (n−1)·F + F}: entry fade, cores with a shared crossfade between, exit fade. */
    private long legacyRunLength() {
        if (eras.length == 0) return 0L;
        long total = (long) legacyFade() * (eras.length + 1);
        for (LegacySpan e : eras) total += e.holdLen();
        return total;
    }

    // ---- queries -------------------------------------------------------------------------

    public long period() {
        return period;
    }

    public int count() {
        return slots.length;
    }

    public Slot slot(int i) {
        return slots[i];
    }

    public long start(int i) {
        return starts[i];
    }

    public long length(int i) {
        return lens[i];
    }

    /** Which occurrence (0-based) of its type slot {@code i} is within the run. */
    public int occurrence(int i) {
        return occurrence[i];
    }

    public int typeCount(Type t) {
        return typeCounts.getOrDefault(t, 0);
    }

    /** Style of the {@code occ}-th (0-based) {@code t} slot of a run, or {@code null} when the run has no such slot. */
    public Style styleOfOccurrence(Type t, int occ) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].type() == t && occurrence[i] == occ) return slots[i].style();
        }
        return null;
    }

    public Fades fades() {
        return fades;
    }

    /** Crossfade length between legacy eras (and the run's entry/exit fades). */
    public int legacyFade() {
        return Math.max(0, fades.legacyFade());
    }

    /** The legacy eras in run order — {@code (kind, 0, fade, core)} each. Never mutated. */
    public LegacySpan[] eras() {
        return eras;
    }

    /** Index of the slot containing base coordinate {@code u}, or {@code -1} outside {@code [0, period)}. */
    public int indexAt(long u) {
        if (u < 0L || u >= period) return -1;
        int lo = 0;
        int hi = slots.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= u) lo = mid;
            else hi = mid - 1;
        }
        return lens[lo] > 0L ? lo : -1;
    }

    /** First slot of {@code t}, or {@code -1}. */
    public int firstIndexOf(Type t) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].type() == t) return i;
        }
        return -1;
    }

    /** Core of the first {@code t} slot (0 when the run has none). */
    public int firstCoreOf(Type t) {
        int i = firstIndexOf(t);
        return i < 0 ? 0 : slots[i].core();
    }

    /**
     * How many {@code t} slots have started at or before base coordinate {@code u}, minus one: the
     * 0-based occurrence inside a {@code t} slot, the last started one between slots, {@code -1} before
     * the run's first.
     */
    public int occurrencesStarted(Type t, long u) {
        int n = -1;
        for (int i = 0; i < slots.length; i++) {
            if (starts[i] > u) break;
            if (slots[i].type() == t) n++;
        }
        return n;
    }

    /** True if any slot overlapping the base window {@code [ua, ub]} is of type {@code t}. */
    public boolean anyOfTypeIn(Type t, long ua, long ub) {
        if (ub < 0L || ua >= period) return false;
        int i = indexAt(Math.max(0L, ua));
        if (i < 0) i = 0;
        for (; i < slots.length && starts[i] <= ub; i++) {
            if (slots[i].type() == t && starts[i] + lens[i] > ua) return true;
        }
        return false;
    }

    /**
     * Where an "approach" to slot {@code i} begins: the end of the nearest earlier slot that is not an
     * overworld gap (or the run start). The approach-or-band windows gate "Re-Over-World".
     */
    public long approachStart(int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (slots[j].type() != Type.OVERWORLD) return starts[j] + lens[j];
        }
        return 0L;
    }

    // ---- legacy run geometry ------------------------------------------------------------

    /** Index of {@code kind} among the run's eras, or {@code -1}. */
    public int eraIndex(LegacyBandKind kind) {
        for (int i = 0; i < eras.length; i++) {
            if (eras[i].kind() == kind) return i;
        }
        return -1;
    }

    /** Offset of era {@code e}'s core from the legacy slot start. */
    public long eraCoreStart(int e) {
        long at = legacyFade();
        for (int i = 0; i < e; i++) at += eras[i].holdLen() + legacyFade();
        return at;
    }

    public long eraCoreLen(int e) {
        return eras[e].holdLen();
    }

    // ---- doubling ------------------------------------------------------------------------

    /** Run index {@code k} of anchored offset {@code off ≥ 0}: run {@code k} spans {@code [P·(2^k−1), P·(2^(k+1)−1))}. */
    public static int runIndex(long off, long period) {
        if (period <= 0L || off < 0L) return 0;
        long q = off / period + 1L;
        return 63 - Long.numberOfLeadingZeros(q);
    }

    public static long runStart(int k, long period) {
        return period * ((1L << k) - 1L);
    }

    /** Base coordinate {@code u ∈ [0, period)} of anchored offset {@code off}: the run-0 position it stretches from. */
    public static long baseCoord(long off, long period) {
        if (period <= 0L || off < 0L) return -1L;
        int k = runIndex(off, period);
        return (off - runStart(k, period)) >> k;
    }

    @Override
    public String toString() {
        return "CycleLayout" + Arrays.toString(slots) + " period=" + period;
    }
}
