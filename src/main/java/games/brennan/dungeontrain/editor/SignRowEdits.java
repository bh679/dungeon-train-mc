package games.brennan.dungeontrain.editor;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Z menu's sign edits on one cell's rows, kept pure so they can be tested without a level.
 * Each returns a new list (or {@code null} when the edit doesn't apply); the input is not changed.
 *
 * <p>Shared text ({@link VariantSign#shared}) is resolved here, at edit time: a text or wax edit
 * on a shared row is written into every shared sign row of the cell, so the placers keep stamping
 * each row's own NBT.</p>
 */
public final class SignRowEdits {

    private SignRowEdits() {}

    /** True when {@code row} is a sign row these edits apply to. */
    public static boolean isSignRow(@Nullable VariantState row) {
        return row != null && !row.isMob() && !row.isGroupRef() && SignVariants.isSign(row.state());
    }

    /** Indices this edit writes to: every shared sign row when {@code idx} is shared, else just it. */
    static List<Integer> targets(List<VariantState> rows, int idx) {
        List<Integer> out = new ArrayList<>();
        if (!rows.get(idx).sign().shared()) {
            out.add(idx);
            return out;
        }
        for (int i = 0; i < rows.size(); i++) {
            VariantState r = rows.get(i);
            if (isSignRow(r) && r.sign().shared()) out.add(i);
        }
        return out;
    }

    /** {@code rows} with {@code text} written into row {@code idx} (and its shared siblings). */
    @Nullable
    public static List<VariantState> setText(List<VariantState> rows, int idx, SignVariants.Text text) {
        if (!valid(rows, idx)) return null;
        List<VariantState> out = new ArrayList<>(rows);
        for (int i : targets(rows, idx)) {
            VariantState r = out.get(i);
            out.set(i, r.withState(r.state(), SignVariants.withText(r.blockEntityNbt(), text)));
        }
        return out;
    }

    /** {@code rows} with the wax on row {@code idx} (and its shared siblings) set to {@code waxed}. */
    @Nullable
    public static List<VariantState> setWaxed(List<VariantState> rows, int idx, boolean waxed) {
        if (!valid(rows, idx)) return null;
        List<VariantState> out = new ArrayList<>(rows);
        for (int i : targets(rows, idx)) {
            VariantState r = out.get(i);
            out.set(i, r.withState(r.state(), SignVariants.withWaxed(r.blockEntityNbt(), waxed)));
        }
        return out;
    }

    /**
     * {@code rows} with shared text turned {@code on} for every sign row of the cell. Turning it
     * on copies row {@code idx}'s text to the others, so the cell starts from what the author is
     * looking at; turning it off leaves each row the text it has.
     */
    @Nullable
    public static List<VariantState> setShared(List<VariantState> rows, int idx, boolean on) {
        if (!valid(rows, idx)) return null;
        SignVariants.Text text = SignVariants.text(rows.get(idx).blockEntityNbt());
        List<VariantState> out = new ArrayList<>(rows);
        for (int i = 0; i < out.size(); i++) {
            VariantState r = out.get(i);
            if (!isSignRow(r)) continue;
            VariantState next = r.withSign(r.sign().withShared(on));
            if (on && i != idx) {
                next = next.withState(next.state(), SignVariants.withText(next.blockEntityNbt(), text));
            }
            out.set(i, next);
        }
        return out;
    }

    /**
     * {@code rows} with row {@code idx} mounted per the wire value: 0–3 a fixed
     * {@link SignVariants.Mount} (clearing Auto), {@link SignVariants#AUTO_WIRE} Auto. {@code null}
     * when the wood has no such block.
     */
    @Nullable
    public static List<VariantState> setMount(List<VariantState> rows, int idx, int wire) {
        if (!valid(rows, idx)) return null;
        VariantState r = rows.get(idx);
        VariantState next;
        if (wire == SignVariants.AUTO_WIRE) {
            next = r.withSign(r.sign().withAuto(true));
        } else if (wire >= 0 && wire < SignVariants.Mount.values().length) {
            SignVariants.Mount mount = SignVariants.Mount.values()[wire];
            if (SignVariants.mountOf(r.state()) != mount && SignVariants.sibling(r.state(), mount) == null) return null;
            next = SignVariants.withMount(r, mount);
            next = next.withSign(r.sign().withAuto(false));
        } else {
            return null;
        }
        List<VariantState> out = new ArrayList<>(rows);
        out.set(idx, next);
        return out;
    }

    private static boolean valid(List<VariantState> rows, int idx) {
        return rows != null && idx >= 0 && idx < rows.size() && isSignRow(rows.get(idx));
    }
}
