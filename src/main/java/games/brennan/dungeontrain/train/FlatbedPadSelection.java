package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.CarriagePlotRows;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.template.GateContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Which flatbed a group's half-flatbed pads are cut from — the built-in flatbed or a flatbed variant
 * ({@link ShellPool#FLATBED}), drawn by weight once per group, so the pad at the group's back and the
 * pad at its front are always halves of the same template.
 *
 * <p>Seeded by the group ordinal, salted apart from the layout and pair draws, so a re-stamped group
 * gets the same flatbed. With no variant weighted in, the answer is always the built-in.</p>
 */
public final class FlatbedPadSelection {

    private static final long SALT = 0x464C41544245445AL; // "FLATBEDZ"

    private FlatbedPadSelection() {}

    /** The pure draw over {@code ids}; {@code fallback} when none has a weight. */
    static String draw(long seed, long groupIndex, List<String> ids, ToIntFunction<String> weight, String fallback) {
        int total = 0;
        for (String id : ids) total += Math.max(0, weight.applyAsInt(id));
        if (total <= 0) return fallback;
        return WholeCarriageSelection.weightedSeededPick(seed ^ SALT, (int) groupIndex, ids, weight);
    }

    /** The flatbed for the group anchored at {@code anchorPIdx}. */
    public static CarriageVariant pick(int anchorPIdx, int groupSize, long worldSeed, GateContext anchorGate) {
        CarriageVariant builtin = CarriagePlacer.flatbedVariant();
        CarriageWeights weights = CarriageWeights.current();
        List<String> ids = new ArrayList<>();
        for (CarriageVariant v : CarriagePlotRows.membersOf(CarriagePlotRows.Row.FLATBEDS)) {
            if (weights.weightFor(v.id()) <= 0) continue;
            // A variant made blank and never built would be a hole between the groups.
            if (!v.isBuiltin() && !CarriageTemplateStore.hasBlocks(v.id())) continue;
            if (anchorGate != null && !anchorGate.allows(weights.gateFor(v.id()))) continue;
            ids.add(v.id());
        }
        long groupIndex = Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
        String chosen = draw(worldSeed, groupIndex, ids, weights::weightFor, builtin.id());
        return CarriageVariantRegistry.find(chosen).orElse(builtin);
    }
}
