package games.brennan.dungeontrain.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a cell's own orientation through a lock-group edit when the group's rotation is free.
 *
 * <p>A lock group shares one candidate list: an edit made in one cell's menu (add, remove, a weight,
 * a condition…) is written to every cell of the group. On Rot Free each cell also owns its rows'
 * rotation ({@link VariantRotation}) and upside-down flip ({@link VariantHalf}), so a sibling must
 * take the edited list <em>with its own orientation kept</em> — otherwise one cell's edit would
 * quietly reset every other cell's facing. Pure and list-in / list-out; nothing passed in changes.</p>
 */
public final class VariantRowOrientation {

    private VariantRowOrientation() {}

    /**
     * {@code edited} with each row's rotation and flip taken from the matching row of {@code own}.
     *
     * <p>Same row count (a weight, mode, condition or replace edit): rows match by position. A
     * different count (a row added or removed): rows match by content — the row with its rotation,
     * flip and weight set aside — first unused match wins. A row with no match (the one just
     * added) keeps the edited cell's orientation.</p>
     */
    public static List<VariantState> keepOwnOrientation(List<VariantState> own, List<VariantState> edited) {
        if (own == null || own.isEmpty() || edited == null) return edited;
        List<VariantState> out = new ArrayList<>(edited.size());
        if (own.size() == edited.size()) {
            for (int i = 0; i < edited.size(); i++) out.add(withOrientationOf(edited.get(i), own.get(i)));
            return List.copyOf(out);
        }
        boolean[] used = new boolean[own.size()];
        for (VariantState row : edited) {
            VariantState key = contentKey(row);
            VariantState match = null;
            for (int j = 0; j < own.size(); j++) {
                if (!used[j] && contentKey(own.get(j)).equals(key)) {
                    used[j] = true;
                    match = own.get(j);
                    break;
                }
            }
            out.add(match == null ? row : withOrientationOf(row, match));
        }
        return List.copyOf(out);
    }

    private static VariantState withOrientationOf(VariantState row, VariantState source) {
        return row.withRotation(source.rotation()).withHalf(source.half());
    }

    /** What a row is, apart from the per-cell orientation and the weight an edit may have changed. */
    private static VariantState contentKey(VariantState row) {
        return row.withRotation(VariantRotation.NONE).withHalf(VariantHalf.NONE).withWeight(1);
    }
}
