package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a cell's group condition on the group it touches. Copying a column (its lock cells and
 * the conditional cells between them) and re-locking it, or pasting a conditional cell beside a
 * different group, used to leave the conditions reading the original group — so the new column
 * came out gappy whenever the two groups rolled differently. After a paste or a lock change the
 * affected cells are retargeted by {@link VariantConditionCheck#retargetFor}'s rule.
 */
public final class VariantConditionRetarget {

    private VariantConditionRetarget() {}

    /** Retargets {@code localPos} and its six neighbours where the rule applies; true when any changed. */
    public static boolean around(BlockVariantPlot plot, BlockPos localPos) {
        boolean changed = follow(plot, localPos);
        for (Direction d : Direction.values()) changed |= follow(plot, localPos.relative(d));
        return changed;
    }

    /** Retargets one cell's group conditions to the single group it touches, if they read another. */
    static boolean follow(BlockVariantPlot plot, BlockPos pos) {
        List<VariantState> states = plot.statesAt(pos);
        if (states == null) return false;
        Map<BlockPos, VariantConditionCheck.Cell> local = new HashMap<>();
        local.put(pos, cellOf(plot, pos, states));
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            List<VariantState> ns = plot.statesAt(n);
            if (ns != null) local.put(n, cellOf(plot, n, ns));
        }
        VariantConditionCheck.Cell cell = local.get(pos);
        Set<Integer> touching = VariantConditionCheck.touchingGroups(local, pos, cell.lockId());
        int to = VariantConditionCheck.retargetFor(cell, touching);
        if (to <= 0) return false;
        int from = cell.groupTargets().iterator().next();
        List<VariantState> next = new ArrayList<>(states.size());
        for (VariantState s : states) {
            VariantCondition c = s.condition();
            next.add(c.isGroupTarget() && c.groupId() == from
                ? s.withCondition(new VariantCondition(0, to, c.blocks())) : s);
        }
        plot.put(pos, next);
        return true;
    }

    private static VariantConditionCheck.Cell cellOf(BlockVariantPlot plot, BlockPos pos, List<VariantState> states) {
        List<VariantConditionCheck.Candidate> candidates = new ArrayList<>(states.size());
        for (VariantState s : states) {
            candidates.add(new VariantConditionCheck.Candidate(VariantCondition.idOf(s.state()), s.condition()));
        }
        return new VariantConditionCheck.Cell(plot.lockIdAt(pos), List.copyOf(candidates));
    }
}
