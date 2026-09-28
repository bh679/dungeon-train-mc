package games.brennan.dungeontrain.command;

import games.brennan.dungeontrain.worldgen.BandLabel;
import games.brennan.dungeontrain.worldgen.BandStages;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Resolves {@code /dtp <band> <subsection> [lap]}: the subsections are the {@link BandStages} of the layout
 * slot the band's occurrence sits in — the same list the F3+4 panel's {@code Stage: k/N} line counts through.
 * The target column is computed exactly from the stage offsets ({@link WorldGenCycle#slotWorldX}), so run
 * doubling is handled without a scan.
 */
final class SubsectionLocator {

    private SubsectionLocator() {}

    /** The subsections {@code /dtp <band>} offers in one lap: the slot's stages, their tokens, and one column inside the slot. */
    record Subsections(List<BandStages.Stage> stages, List<String> tokens, List<Integer> offered, int slotX) {

        /** Tokens offered for this band, in +X order. */
        List<String> offeredTokens() {
            List<String> out = new ArrayList<>(offered.size());
            for (int i : offered) out.add(tokens.get(i));
            return List.copyOf(out);
        }

        /** Stage index for an offered {@code token}, or -1. */
        int indexOf(String token) {
            for (int i : offered) {
                if (tokens.get(i).equals(token)) return i;
            }
            return -1;
        }
    }

    /** The subsections of {@code target}'s occurrence in {@code lap}; empty when that lap has no such band or the cycle has no layout. */
    static Optional<Subsections> of(ServerLevel overworld, DtpTarget target, int lap) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        OptionalInt entry = BandLocator.bandStartXInLap(overworld, target, lap);
        if (entry.isEmpty()) return Optional.empty();
        int slot = cycle.slotIndexAt(entry.getAsInt());
        if (slot < 0) return Optional.empty();
        return of(cycle, BandLabel.stagesOf(cycle, slot), target.token(), entry.getAsInt());
    }

    /** Pure form: {@code stages} of the slot containing {@code slotX}, filtered for {@code bandToken}. */
    static Optional<Subsections> of(WorldGenCycle cycle, List<BandStages.Stage> stages, String bandToken, int slotX) {
        int slot = cycle.slotIndexAt(slotX);
        if (slot < 0 || stages.isEmpty()) return Optional.empty();
        List<String> tokens = BandStages.tokens(stages);
        boolean legacyEra = cycle.layout().slot(slot).type() == CycleLayout.Type.LEGACY_RUN && isLegacyEra(bandToken);
        List<Integer> offered = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!legacyEra || mentions(tokens.get(i), bandToken)) offered.add(i);
        }
        if (offered.isEmpty()) return Optional.empty();
        return Optional.of(new Subsections(stages, tokens, List.copyOf(offered), slotX));
    }

    /**
     * World X {@code inset} blocks into stage {@code index} of {@code subs} — clamped to the stage's middle so a
     * short stage is still landed inside.
     */
    static OptionalLong targetX(WorldGenCycle cycle, Subsections subs, int index, int inset) {
        if (index < 0 || index >= subs.stages().size()) return OptionalLong.empty();
        long start = cycle.slotWorldX(subs.slotX(), BandStages.startOf(subs.stages(), index));
        if (start == Long.MIN_VALUE) return OptionalLong.empty();
        long worldLen = subs.stages().get(index).length() * cycle.runScaleAt(subs.slotX());
        return OptionalLong.of(start + Math.min(inset, worldLen / 2));
    }

    /** The legacy eras share one slot; each era's band offers only the stages that name it. */
    private static boolean isLegacyEra(String bandToken) {
        for (LegacyBandKind k : LegacyBandKind.values()) {
            if (k.token().equals(bandToken)) return true;
        }
        return false;
    }

    /** {@code amplified_to_beta} mentions {@code beta}; whole underscore-separated words only. */
    private static boolean mentions(String stageToken, String bandToken) {
        return ("_" + stageToken + "_").contains("_" + bandToken + "_");
    }
}
