package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stages a train passes through inside one {@link CycleLayout} slot, in +X order and fades
 * included — what the F3+4 panel's {@code Stage: k/N} line counts through.
 *
 * <p>Each list is built from the same terms {@code CycleLayout.slotLength} sums, so the stage lengths
 * add up to the slot's own length and a position can never fall between two stages. All offsets are
 * in <b>base</b> blocks (run 0 scale); under the doubling layout run {@code k} stretches every stage by
 * the same {@code 2^k}, so a fraction through a stage reads the same in every run.</p>
 *
 * <p>Pure — no Minecraft types, no config reads — so it unit-tests against {@link CycleLayout#DEFAULT_ORDER}
 * directly. Zero-length stages are dropped, so a disabled mega-hold or an empty Reassembly doesn't
 * count as a stage the player can never be in.</p>
 */
public final class BandStages {

    /** One stage: a name and its length in base blocks. */
    public record Stage(String name, long length) {}

    /** Where a position sits: {@code index} into the list (0-based) and {@code fraction} {@code 0..1} through it. */
    public record Position(int index, int count, String name, double fraction) {
        /** {@code "6/7 Structure boost (42%)"}. */
        public String describe() {
            return (index + 1) + "/" + count + " " + name + " (" + (int) Math.floor(fraction * 100.0) + "%)";
        }
    }

    private BandStages() {}

    /**
     * Stages of slot {@code i}.
     *
     * @param stageCount  Nether mountain stages ({@code stageMultipliers.length}, at least 1)
     * @param stageBlocks length of each Nether mountain stage
     * @param beachBlocks the Nether's leading (and mirrored trailing) beach
     * @param spheres     the spheres core progression
     */
    public static List<Stage> of(CycleLayout layout, int i, int stageCount, int stageBlocks, int beachBlocks,
                                 SpheresSegments spheres) {
        return of(layout, i, stageCount, stageBlocks, beachBlocks, spheres, 0, 0);
    }

    /**
     * {@link #of(CycleLayout, int, int, int, int, SpheresSegments)} with the spheres band's closing stretch:
     * the last {@code exitTaperBlocks} before the final {@code exitVoidBlocks} are "Thinning out", the
     * final ones "Closing void".
     */
    public static List<Stage> of(CycleLayout layout, int i, int stageCount, int stageBlocks, int beachBlocks,
                                 SpheresSegments spheres, int exitTaperBlocks, int exitVoidBlocks) {
        CycleLayout.Slot slot = layout.slot(i);
        CycleLayout.Fades f = layout.fades();
        List<Stage> out = new ArrayList<>();
        long core = Math.max(0, slot.core());
        switch (slot.type()) {
            case OVERWORLD -> add(out, "Gap", core);
            case NETHER -> nether(out, f, core, stageCount, stageBlocks, beachBlocks);
            case END -> {
                long fade = Math.max(0, f.eFade());
                long hold = Math.max(0, f.eVoid());
                add(out, "Erosion fade-in", fade);
                add(out, "Void", hold);
                add(out, "Islands fade-in", fade);
                add(out, "End core", core);
                add(out, "Islands fade-out", fade);
                add(out, "Void", hold);
                add(out, "Erosion fade-out", fade);
            }
            case UPSIDE_DOWN -> {
                long fade = Math.max(0, f.udFade());
                add(out, "Entry fade", fade);
                add(out, "Core", core);
                add(out, "Trailing fade", fade);
                add(out, "Reassembly", layout.udReassembly(slot));
                add(out, "Exit gap", Math.max(0, f.udExit()));
            }
            case CHUNCKS -> transitionThenCore(out, f.chuncksFade(), core);
            case STACKS -> transitionThenCore(out, f.stacksFade(), core);
            case SPHERES -> {
                add(out, "Transition in", Math.max(0, f.spheresFade()));
                spheres(out, core, spheres, exitTaperBlocks, exitVoidBlocks);
            }
            case LEGACY_RUN -> legacy(out, layout);
        }
        return List.copyOf(out);
    }

    /** Which stage {@code local} (base blocks into the slot) falls in; {@code null} for an empty list or a negative offset. */
    public static Position locate(List<Stage> stages, long local) {
        if (stages.isEmpty() || local < 0L) return null;
        long at = 0L;
        for (int s = 0; s < stages.size(); s++) {
            Stage stage = stages.get(s);
            if (local < at + stage.length() || s == stages.size() - 1) {
                double fraction = Math.min(1.0, Math.max(0.0, (local - at) / (double) stage.length()));
                return new Position(s, stages.size(), stage.name(), fraction);
            }
            at += stage.length();
        }
        return null;
    }

    /**
     * {@code [beach][stage 1..n][mega][coreFade][core][coreFade][mega][stage n..1][beach]}. When the rise
     * doesn't split cleanly into beach + stages (a config the cycle clamped differently), the rise is one
     * stage rather than a guess.
     */
    private static void nether(List<Stage> out, CycleLayout.Fades f, long core,
                               int stageCount, int stageBlocks, int beachBlocks) {
        int n = Math.max(1, stageCount);
        long beach = Math.max(0, beachBlocks);
        long step = Math.max(0, stageBlocks);
        long rise = Math.max(0, f.riseLen());
        boolean split = beach + n * step == rise;
        long mega = Math.max(0, f.megaHold());
        long coreFade = Math.max(0, f.coreFade());

        if (split) {
            add(out, "Beach", beach);
            for (int s = 1; s <= n; s++) add(out, "Mountain " + s, step);
        } else {
            add(out, "Rise", rise);
        }
        add(out, "Mega hold", mega);
        add(out, "Core fade-in", coreFade);
        add(out, "Core", core);
        add(out, "Core fade-out", coreFade);
        add(out, "Mega hold", mega);
        if (split) {
            for (int s = n; s >= 1; s--) add(out, "Mountain " + s, step);
            add(out, "Beach", beach);
        } else {
            add(out, "Fall", rise);
        }
    }

    private static void transitionThenCore(List<Stage> out, int fade, long core) {
        add(out, "Transition in", Math.max(0, fade));
        add(out, "Core", core);
    }

    /**
     * The spheres core cut at each progression breakpoint, in offset order. Breakpoints past the core or
     * on top of one another collapse, so a shortened band shows only the stages it actually reaches.
     */
    private static void spheres(List<Stage> out, long core, SpheresSegments seg, int exitTaper, int exitVoid) {
        long voidStart = core - Math.max(0, Math.min(exitVoid, core));
        long taperStart = voidStart - Math.max(0, Math.min(exitTaper, voidStart));
        List<Breakpoint> points = new ArrayList<>();
        points.add(new Breakpoint(0L, "Overworld sky"));
        points.add(new Breakpoint(seg.endSkyStart(), "End sky"));
        points.add(new Breakpoint(seg.netherMixStart(), "Nether spheres join"));
        points.add(new Breakpoint(seg.endMixStart(), "End spheres join"));
        points.add(new Breakpoint(seg.structureBoostStart(), "Structure boost"));
        // Inside the taper the stretch still reads as thinning out, not "boost over".
        if (seg.structureBoostEnd() < taperStart || seg.structureBoostEnd() >= voidStart) {
            points.add(new Breakpoint(seg.structureBoostEnd(), "Boost over"));
        }
        if (taperStart < voidStart) points.add(new Breakpoint(taperStart, "Thinning out"));
        if (voidStart < core) points.add(new Breakpoint(voidStart, "Closing void"));
        points.sort((a, b) -> Long.compare(a.offset(), b.offset()));

        // Several breakpoints at one offset: the last one listed there names the stage that follows.
        List<Breakpoint> merged = new ArrayList<>();
        for (Breakpoint p : points) {
            if (p.offset() >= core && p.offset() != 0L) continue;
            if (!merged.isEmpty() && merged.get(merged.size() - 1).offset() == p.offset()) {
                merged.set(merged.size() - 1, p);
            } else {
                merged.add(p);
            }
        }
        for (int s = 0; s < merged.size(); s++) {
            long end = s + 1 < merged.size() ? merged.get(s + 1).offset() : core;
            add(out, merged.get(s).name(), end - merged.get(s).offset());
        }
    }

    private record Breakpoint(long offset, String name) {}

    /** {@code [fade][era0][crossfade][era1]…[eraN-1][fade]}. */
    private static void legacy(List<Stage> out, CycleLayout layout) {
        LegacySpan[] eras = layout.eras();
        if (eras.length == 0) return;
        long fade = layout.legacyFade();
        add(out, "Fade into " + pretty(eras[0].kind().token()), fade);
        for (int e = 0; e < eras.length; e++) {
            String name = pretty(eras[e].kind().token());
            add(out, name, layout.eraCoreLen(e));
            String next = e + 1 < eras.length ? pretty(eras[e + 1].kind().token()) : "Overworld";
            add(out, name + " → " + next, fade);
        }
    }

    /** {@code far_lands} → {@code Far Lands}. */
    static String pretty(String token) {
        StringBuilder sb = new StringBuilder();
        for (String word : token.split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return sb.toString();
    }

    private static void add(List<Stage> out, String name, long length) {
        if (length > 0L) out.add(new Stage(name, length));
    }
}
