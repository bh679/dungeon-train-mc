package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.compat.vista.LiveTvWeighting;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * How a {@link VariantDoc} rolls a cell once the candidate list has been narrowed to the tier — the
 * one per-kind difference in spawn-time resolution, stated as a strategy rather than copied into a
 * second document class.
 *
 * <p>{@link #PLAIN} is the deterministic weighted / lock-group pick every sidecar makes.
 * {@link #LIVE_TV} is what contents sidecars do on top of it: a cell holding a TV is reweighted for
 * the live stream ({@link LiveTvWeighting}) before the roll. A cell with no TV, one holding a
 * lock-group reference, a gated cell, or a roll with no server behind it falls back to the plain
 * pick, so a contents sidecar with no TV in it rolls exactly as every other kind.</p>
 */
public enum VariantPick {

    /** The deterministic weighted / lock-group pick. */
    PLAIN {
        @Override
        VariantState pick(VariantGroupResolver groupRefs, BlockPos localPos, int lockId,
                          List<VariantState> states, long worldSeed, int tileIndex) {
            return groupRefs.resolve(localPos, lockId, states, worldSeed, tileIndex);
        }
    },

    /** {@link #PLAIN} with TV entries reweighted for the live stream. */
    LIVE_TV {
        @Override
        VariantState pick(VariantGroupResolver groupRefs, BlockPos localPos, int lockId,
                          List<VariantState> states, long worldSeed, int tileIndex) {
            // A gated cell takes the authored roll: the live reweighting has no notion of conditions.
            int[] weights = VariantConditions.anyConditional(states) ? null : liveTvWeights(states, tileIndex);
            if (weights != null) {
                VariantState picked = VariantGroupResolver.resolveWeighted(
                    localPos, lockId, states, worldSeed, tileIndex, weights);
                if (picked != null) return picked;
            }
            return PLAIN.pick(groupRefs, localPos, lockId, states, worldSeed, tileIndex);
        }
    };

    abstract VariantState pick(VariantGroupResolver groupRefs, BlockPos localPos, int lockId,
                               List<VariantState> states, long worldSeed, int tileIndex);

    /** The live-adjusted weight column for {@code states}, or {@code null} when nothing changes. */
    private static int[] liveTvWeights(List<VariantState> states, int carriageIndex) {
        if (!LiveTvWeighting.active() || VariantGroupRefs.anyRef(states)) return null;
        boolean anyTv = false;
        for (VariantState s : states) {
            if (LiveTvWeighting.isTv(s)) { anyTv = true; break; }
        }
        if (!anyTv) return null;
        int[] weights = new int[states.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = LiveTvWeighting.variantWeight(states.get(i), carriageIndex);
        }
        return weights;
    }
}
