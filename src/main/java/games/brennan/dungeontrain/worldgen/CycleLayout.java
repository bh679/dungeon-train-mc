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
 * lap 2), and the legacy eras share {@link Type#LEGACY_RUN} slots in which they crossfade straight
 * into each other (an order may hold several legacy slots; each era belongs to at most one). End slots
 * written back to back join into <b>one</b> continuous End band — a single void fade in and out, the
 * pieces' cores laid end to end — while each piece stays its own occurrence with its own look
 * ({@code end:vanilla:1200, end:bop:2000}). Slot lengths are {@code core + the band's own fades}, computed from the
 * {@link Fades} the cycle carries, so every band's existing ramp maths keeps working at a slot-local
 * offset. {@link #period()} is the length of run 0; later runs stretch — see {@link #runIndex} /
 * {@link #baseCoord}: run {@code k} is {@code 2^k} times as long and every ramp is evaluated at the
 * base coordinate {@code u} in {@code [0, period)}.</p>
 *
 * <p>Parsed from the {@code worldgenCycleOrder} COMMON key ({@link #parse}): comma-separated
 * {@code slot[:style][:length]} tokens — {@code ow}, {@code nether}, {@code end}, {@code upside_down}
 * ({@code :core:reassembly}), {@code chuncks}, {@code spheres}, {@code stacks}, {@code mix}, and {@code legacy}
 * ({@code :kind=core} per era, run in the order written, or bare for every enabled era at its configured
 * length in declaration order). A style may be {@code first>later} (one look on the first run, another
 * after), and a Nether's first look may be a split {@code a=N+b} — look {@code a} for the first {@code N}
 * core blocks of the first run, then {@code b} ({@code nether:vanilla=1000+bop>bop}); {@code a=N~M+b} mixes the two looks over {@code M} blocks between. A band whose
 * config flag is off is dropped from the layout wherever the order names it.</p>
 */
public final class CycleLayout {

    /** The kinds of slot a run is made of. */
    public enum Type { OVERWORLD, NETHER, END, UPSIDE_DOWN, CHUNCKS, SPHERES, STACKS, LEGACY_RUN,
        /** Hard-edged zone where every chunk generates as a band the run has already passed; see {@link MixBand}. */
        MIX }

    /**
     * Which look an occurrence wears. A label the band's own code reads
     * ({@code WorldGenCycle#netherStyleAt} etc.); the layout itself is style-agnostic. {@code SUNK} is an
     * overworld gap generated at the sunk Amplified band's height ({@code worldgen.SunkZone}), so the
     * approach to that band is already low.
     */
    public enum Style { VANILLA, WWOO, BOP, BETTER, SUNK }

    /**
     * One parsed slot. {@code core} is the band's full-strength length; {@code extra} is the
     * upside-down slot's Reassembly (exit-fade) length, or {@code -1} for the cycle's default.
     * {@code style} is the look on the first run; {@code laterStyle} the look on every run after it
     * (the same unless the order wrote {@code first>later}, e.g. {@code nether:vanilla>bop}).
     * {@code splitAt} / {@code splitBlend} / {@code splitStyle}: on the first run only, the core wears
     * {@code style} for its first {@code splitAt} blocks, mixes the two looks over the next {@code splitBlend}
     * blocks, then wears {@code splitStyle} ({@code nether:vanilla=1000~400+bop>bop}); {@code -1} / {@code 0} /
     * {@code null} for no split.
     */
    public record Slot(Type type, Style style, int core, int extra, Style laterStyle,
                       int splitAt, int splitBlend, Style splitStyle) {

        /** A slot without a first-run split. */
        public Slot(Type type, Style style, int core, int extra, Style laterStyle) {
            this(type, style, core, extra, laterStyle, -1, 0, null);
        }

        /** A slot with a hard-edged first-run split (no mix). */
        public Slot(Type type, Style style, int core, int extra, Style laterStyle, int splitAt, Style splitStyle) {
            this(type, style, core, extra, laterStyle, splitAt, 0, splitStyle);
        }

        /** A slot with the same look on every run. */
        public Slot(Type type, Style style, int core, int extra) {
            this(type, style, core, extra, style);
        }

        /** True when the first run switches look partway through the core. */
        public boolean hasSplit() {
            return splitAt >= 0 && splitStyle != null;
        }

        /** The look on doubling run {@code run}: {@link #style} on run 0, {@link #laterStyle} after. */
        public Style styleOnRun(long run) {
            return run <= 0L ? style : laterStyle;
        }
    }

    /**
     * The fade geometry slot lengths depend on — the same numbers the cycle record carries for the
     * classic layout. {@code riseLen} is the Nether's beach + mountain stages.
     */
    public record Fades(int riseLen, int megaHold, int coreFade, int eFade, int eVoid,
                        int udFade, int udExit, int udExitFade,
                        int chuncksFade, int spheresFade, int stacksFade, int legacyFade) {}

    /**
     * The default order: the layout {@code build()} uses when the key is blank.
     * <ul>
     *   <li>Lap 1: overworld → Nether (on the first cycle 1000 core blocks of vanilla, then Biomes O' Plenty;
     *       all Biomes O' Plenty after: {@code vanilla=1000~400+bop>bop} — 1000 vanilla, 400 mixed, 1350 BoP) → WWOO overworld → one End band whose first 1200 blocks are vanilla and last 2000
     *       Biomes O' Plenty (two joined End slots) → upside-down + Reassembly.</li>
     *   <li>Lap 2: BoP overworld → BetterNether → Lost City (its own legacy run, wearing WWOO decoration; its
     *       buildings start on the Nether's exit mountains — {@link #legacyLeadIn}) → BetterEnd.</li>
     *   <li>Then spheres, the sunk approach, the rest of the legacy eras, chuncks, mix and stacks.</li>
     * </ul>
     */
    public static final String DEFAULT_ORDER =
            "ow:2750, nether:vanilla=1000~400+bop>bop:2750, ow:wwoo:3250, end:vanilla:1200, end:bop:2000, upside_down:2500:3000, "
            + "ow:bop:4500, nether:better:4500, legacy:wwoo:lost_city=3000, end:better:5000, spheres:6550, ow:sunk:500, "
            + "legacy:amplified=4000:beta=2500:far_lands=4320:caves_of_chaos=3500:skylands=4000:floating=2000:alpha=2000:infdev=2000:classic=2000:superflat=1000:void=200, "
            + "ow:650, chuncks:2000, mix:4000, stacks:5000";

    private final Slot[] slots;
    private final long[] starts;
    private final long[] lens;
    private final int[] occurrence;
    /** Eras of each slot, index-aligned with {@link #slots}: empty for every slot but a legacy run. */
    private final LegacySpan[][] eras;
    private final Fades fades;
    private final long period;
    private final Map<Type, Integer> typeCounts = new EnumMap<>(Type.class);

    private CycleLayout(Slot[] slots, LegacySpan[][] eras, Fades fades) {
        this.slots = slots;
        this.eras = eras;
        this.fades = fades;
        this.starts = new long[slots.length];
        this.lens = new long[slots.length];
        this.occurrence = new int[slots.length];
        long at = 0L;
        for (int i = 0; i < slots.length; i++) {
            starts[i] = at;
            lens[i] = slotLength(i);
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
        List<LegacySpan[]> eras = new ArrayList<>();
        java.util.Set<LegacyBandKind> usedEras = java.util.EnumSet.noneOf(LegacyBandKind.class);
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
                LegacySpan[] run = parseEras(parts, legacyDefaults, usedEras, warn);
                if (run.length == 0) continue;
                for (LegacySpan e : run) usedEras.add(e.kind());
                slots.add(new Slot(type, legacyStyle(parts), 0, -1));
                eras.add(run);
                continue;
            }
            Style style = Style.VANILLA;
            int core = -1;
            int extra = -1;
            Style later = null;
            int splitAt = -1;
            int splitBlend = 0;
            Style splitStyle = null;
            for (int i = 1; i < parts.length; i++) {
                String arg = parts[i].trim().toLowerCase(Locale.ROOT);
                int gt = arg.indexOf('>');
                if (gt >= 0 || arg.indexOf('+') >= 0) {
                    // first>later: one look on the first run, another on every run after it;
                    // the first may be a split a=N+b (look a for N core blocks, then b)
                    String firstArg = gt >= 0 ? arg.substring(0, gt).trim() : arg;
                    Split split = parseSplit(firstArg);
                    Style first = split != null ? split.first() : styleOf(firstArg);
                    Style after = gt >= 0 ? styleOf(arg.substring(gt + 1).trim()) : null;
                    if (first == null || (gt >= 0 && after == null) || (gt < 0 && split == null)) {
                        warn.accept("bad style switch '" + arg + "' on '" + token + "'");
                        continue;
                    }
                    style = first;
                    later = after;
                    splitAt = split != null ? split.at() : -1;
                    splitBlend = split != null ? split.blend() : 0;
                    splitStyle = split != null ? split.then() : null;
                    continue;
                }
                Style s = styleOf(arg);
                if (s != null) {
                    style = s;
                    later = null;
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
            if (splitAt >= 0 && type != Type.NETHER) {
                warn.accept("style split on '" + token + "' only applies to Nether slots; ignored");
                splitAt = -1;
                splitBlend = 0;
                splitStyle = null;
            }
            Style afterRun0 = later != null ? later : splitStyle != null ? splitStyle : style;
            slots.add(new Slot(type, style, core, extra, afterRun0, splitAt, splitBlend, splitStyle));
            eras.add(NO_ERAS);
        }
        if (slots.isEmpty()) return null;
        return new CycleLayout(slots.toArray(new Slot[0]), eras.toArray(new LegacySpan[0][]), fades);
    }

    /**
     * A parsed {@code a=N~M+b} first-run split: look {@code first} for {@code at} core blocks, the two
     * looks mixed over the next {@code blend} ({@code 0} when {@code ~M} is omitted), then {@code then}.
     */
    private record Split(Style first, int at, int blend, Style then) {}

    /** Parse {@code a=N+b} or {@code a=N~M+b}; {@code null} when {@code arg} is not a well-formed split. */
    private static Split parseSplit(String arg) {
        int eq = arg.indexOf('=');
        int plus = arg.indexOf('+');
        if (eq <= 0 || plus <= eq) return null;
        Style first = styleOf(arg.substring(0, eq).trim());
        Style then = styleOf(arg.substring(plus + 1).trim());
        if (first == null || then == null) return null;
        String lengths = arg.substring(eq + 1, plus).trim();
        int tilde = lengths.indexOf('~');
        try {
            int at = Integer.parseInt((tilde < 0 ? lengths : lengths.substring(0, tilde)).trim());
            int blend = tilde < 0 ? 0 : Integer.parseInt(lengths.substring(tilde + 1).trim());
            return at < 0 || blend < 0 ? null : new Split(first, at, blend, then);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final LegacySpan[] NO_ERAS = new LegacySpan[0];

    /** Build directly from slots (tests); every legacy-run slot shares {@code eras}. */
    public static CycleLayout of(List<Slot> slots, LegacySpan[] eras, Fades fades) {
        LegacySpan[][] perSlot = new LegacySpan[slots.size()][];
        for (int i = 0; i < perSlot.length; i++) {
            perSlot[i] = slots.get(i).type() == Type.LEGACY_RUN ? eras : NO_ERAS;
        }
        return new CycleLayout(slots.toArray(new Slot[0]), perSlot, fades);
    }


    /**
     * One legacy run's eras. Named eras run in the order written; a bare {@code legacy} takes every enabled
     * era in {@link LegacyBandKind} declaration order not already in an earlier run. An era named twice —
     * in this run or an earlier one ({@code used}) — keeps its first position.
     */
    private static LegacySpan[] parseEras(String[] parts, LegacySpan[] defaults, java.util.Set<LegacyBandKind> used,
                                          Consumer<String> warn) {
        Map<LegacyBandKind, Integer> cores = new java.util.LinkedHashMap<>();
        boolean explicit = false;
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i].trim().toLowerCase(Locale.ROOT);
            if (styleOf(arg) != null) continue;                     // the run's look, not an era
            explicit = true;
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
            if (cores.containsKey(kind) || used.contains(kind)) {
                warn.accept("legacy era '" + kindName + "' named twice; keeping the first");
                continue;
            }
            cores.put(kind, len);
        }
        Iterable<LegacyBandKind> order = explicit ? cores.keySet() : Arrays.asList(LegacyBandKind.values());
        List<LegacySpan> out = new ArrayList<>();
        for (LegacyBandKind kind : order) {
            if (used.contains(kind)) continue;
            LegacySpan d = defaultOf(defaults, kind);
            if (d == null || d.holdLen() <= 0L) continue;             // disabled in config
            Integer len = cores.get(kind);
            int core = len == null || len < 0 ? d.hold() : len;
            if (core <= 0) continue;
            out.add(new LegacySpan(kind, 0, d.fade(), core));
        }
        return out.toArray(new LegacySpan[0]);
    }

    /**
     * The look a legacy run wears: a style name among its parts ({@code legacy:wwoo:lost_city=4000} gives
     * the Lost City run William Wythers' Overhauled Overworld decoration), {@code VANILLA} otherwise. Only
     * a vanilla-terrain era shows it — an old generator writes its own terrain and decoration.
     */
    private static Style legacyStyle(String[] parts) {
        Style style = Style.VANILLA;
        for (int i = 1; i < parts.length; i++) {
            Style s = styleOf(parts[i].trim().toLowerCase(Locale.ROOT));
            if (s != null) style = s;
        }
        return style;
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
            case "mix" -> Type.MIX;
            default -> null;
        };
    }

    private static Style styleOf(String arg) {
        return switch (arg) {
            case "vanilla" -> Style.VANILLA;
            case "wwoo" -> Style.WWOO;
            case "bop" -> Style.BOP;
            case "better" -> Style.BETTER;
            case "sunk" -> Style.SUNK;
            default -> null;
        };
    }

    private static LegacyBandKind kindOf(String name) {
        for (LegacyBandKind k : LegacyBandKind.values()) {
            if (k.token().equals(name) || k.token().replace("_", "").equals(name)) return k;
        }
        return null;
    }

    /** Full length of slot {@code i}: its core plus the band's own fades. */
    private long slotLength(int i) {
        Slot s = slots[i];
        return switch (s.type()) {
            case OVERWORLD -> Math.max(0, s.core());
            case NETHER -> NetherTransition.bandLength(fades.riseLen(), fades.megaHold(), fades.coreFade(), s.core());
            case END -> endPieceLength(i);
            case UPSIDE_DOWN -> 2L * Math.max(0, fades.udFade()) + s.core() + udReassembly(s) + Math.max(0, fades.udExit());
            case CHUNCKS -> Math.max(0, fades.chuncksFade()) + s.core();
            case SPHERES -> Math.max(0, fades.spheresFade()) + s.core();
            case STACKS -> Math.max(0, fades.stacksFade()) + s.core();
            case LEGACY_RUN -> legacyRunLength(i);
            case MIX -> Math.max(0, s.core());
        };
    }

    /** The upside-down slot's Reassembly (exit crossfade) length — its own, or the cycle default. */
    public long udReassembly(Slot s) {
        return Math.max(0, s.extra() >= 0 ? s.extra() : fades.udExitFade());
    }

    /**
     * Length of End slot {@code i}. A lone End is the whole {@link Disintegration#bandLength}; in a joined
     * run of back-to-back End slots the first piece carries only the entry side (erosion, void, islands
     * fade-in), the last only the exit side, and a middle piece just its core.
     */
    private long endPieceLength(int i) {
        long side = 2L * Math.max(0, fades.eFade()) + Math.max(0, fades.eVoid());
        long len = Math.max(0, slots[i].core());
        if (!isEnd(i - 1)) len += side;
        if (!isEnd(i + 1)) len += side;
        return len;
    }

    private boolean isEnd(int i) {
        return i >= 0 && i < slots.length && slots[i].type() == Type.END;
    }

    /**
     * How far legacy slot {@code slot}'s entry fade reaches back into the slot before it: the preceding
     * Nether's exit mountains ({@code megaHold + riseLen}) when the run opens with a vanilla-terrain era
     * (Lost City) — its buildings start on the slopes as the mountains come down. {@code 0} otherwise:
     * an old generator's terrain can't share the Nether's. Slot lengths are unaffected.
     */
    public long legacyLeadIn(int slot) {
        if (slot <= 0 || slot >= slots.length || slots[slot].type() != Type.LEGACY_RUN) return 0L;
        if (slots[slot - 1].type() != Type.NETHER) return 0L;
        LegacySpan[] run = eras[slot];
        if (run.length == 0 || !run[0].kind().usesVanillaTerrain()) return 0L;
        return Math.min(lens[slot - 1], (long) Math.max(0, fades.megaHold()) + Math.max(0, fades.riseLen()));
    }

    /**
     * Base offset, within the cycle, where Nether slot {@code i}'s real-Nether core begins: past its beach,
     * mountain stages, plateau and rock → netherrack crossfade ({@link NetherTransition#bandLength}'s layout).
     */
    public long netherCoreStart(int i) {
        return starts[i] + Math.max(0, fades.riseLen()) + Math.max(0, fades.megaHold()) + Math.max(0, fades.coreFade());
    }

    /** {@code Σ fadeBefore(e) + Σcore}: each era's own entry fade / crossfade, the cores, then the exit fade. */
    private long legacyRunLength(int slot) {
        LegacySpan[] run = eras[slot];
        if (run.length == 0) return 0L;
        long total = 0L;
        for (int e = 0; e <= run.length; e++) total += fadeBefore(slot, e);
        for (LegacySpan e : run) total += e.holdLen();
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

    /** Slot index of the {@code occ}-th (0-based) {@code t} slot of a run, or {@code -1}. */
    public int indexOfOccurrence(Type t, int occ) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].type() == t && occurrence[i] == occ) return i;
        }
        return -1;
    }

    public Fades fades() {
        return fades;
    }

    // ---- joined End bands ----------------------------------------------------------------

    /** First slot of the joined End band slot {@code i} belongs to ({@code i} itself for a lone End). */
    public int endGroupFirst(int i) {
        while (isEnd(i - 1)) i--;
        return i;
    }

    /** Base offset where the joined End band containing slot {@code i} starts. */
    public long endGroupStart(int i) {
        return starts[endGroupFirst(i)];
    }

    /** Summed core of the joined End band containing slot {@code i} — the core the band's ramps see. */
    public int endGroupCore(int i) {
        int core = 0;
        for (int j = endGroupFirst(i); isEnd(j); j++) core += Math.max(0, slots[j].core());
        return core;
    }

    /** Number of End slots in the joined End band containing slot {@code i}. */
    public int endGroupSize(int i) {
        int n = 0;
        for (int j = endGroupFirst(i); isEnd(j); j++) n++;
        return n;
    }

    /** Whole length of the joined End band containing slot {@code i}. */
    public long endGroupLength(int i) {
        long len = 0L;
        for (int j = endGroupFirst(i); isEnd(j); j++) len += lens[j];
        return len;
    }

    /** A legacy run's exit fade, and the fallback for an era with no fade of its own. */
    public int legacyFade() {
        return Math.max(0, fades.legacyFade());
    }

    /**
     * Length of the fade <em>into</em> era {@code e} of legacy slot {@code slot}: the run's entry fade for
     * {@code e == 0}, the crossfade from era {@code e − 1} otherwise — each era's own configured fade
     * ({@code legacy<Era>FadeBlocks}), so one seam can be longer than the rest (Lost City's 750-block run-in).
     * {@code e} equal to the era count is the run's exit fade ({@link #legacyFade}).
     */
    public long fadeBefore(int slot, int e) {
        LegacySpan[] run = eras(slot);
        if (e < 0 || e >= run.length) return legacyFade();
        int f = run[e].fade();
        return f >= 0 ? f : legacyFade();
    }

    /** Legacy slot {@code slot}'s eras in run order — {@code (kind, 0, fade, core)} each; empty for any other slot. Never mutated. */
    public LegacySpan[] eras(int slot) {
        return slot < 0 || slot >= eras.length ? NO_ERAS : eras[slot];
    }

    /** Every era of every legacy run, in layout order. */
    public List<LegacySpan> allEras() {
        List<LegacySpan> out = new ArrayList<>();
        for (LegacySpan[] run : eras) out.addAll(Arrays.asList(run));
        return out;
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

    /** The legacy slot that runs era {@code kind}, or {@code -1}. */
    public int legacySlotOf(LegacyBandKind kind) {
        for (int s = 0; s < eras.length; s++) {
            for (LegacySpan e : eras[s]) {
                if (e.kind() == kind) return s;
            }
        }
        return -1;
    }

    /** Index of {@code kind} among its own legacy slot's eras, or {@code -1}. */
    public int eraIndex(LegacyBandKind kind) {
        int s = legacySlotOf(kind);
        if (s < 0) return -1;
        for (int i = 0; i < eras[s].length; i++) {
            if (eras[s][i].kind() == kind) return i;
        }
        return -1;
    }

    /** Offset of era {@code e}'s core from legacy slot {@code slot}'s start. */
    public long eraCoreStart(int slot, int e) {
        long at = fadeBefore(slot, 0);
        for (int i = 0; i < e; i++) at += eras[slot][i].holdLen() + fadeBefore(slot, i + 1);
        return at;
    }

    public long eraCoreLen(int slot, int e) {
        return eras[slot][e].holdLen();
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
