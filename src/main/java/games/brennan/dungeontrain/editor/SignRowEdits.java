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
 *
 * <p>Locked rows ({@link VariantSign#locked}) go further: a mount or Auto edit on one is copied
 * to every locked row too ({@link SignVariants#copyPlacement}), so the cell's signs differ only
 * in wood.</p>
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
     * {@code rows} with every sign row locked to row {@code idx} ({@code on}) or unlocked. Locking
     * copies row {@code idx}'s mount, facing, rotation, Auto, text and wax onto the other sign rows
     * (each keeps its wood); unlocking clears the lock only, so the text stays shared and nothing
     * visible changes.
     */
    @Nullable
    public static List<VariantState> setLocked(List<VariantState> rows, int idx, boolean on) {
        if (!valid(rows, idx)) return null;
        VariantState leader = rows.get(idx);
        List<VariantState> out = new ArrayList<>(rows);
        for (int i = 0; i < out.size(); i++) {
            VariantState r = out.get(i);
            if (!isSignRow(r)) continue;
            out.set(i, on ? follow(leader, r, i == idx) : r.withSign(r.sign().withLocked(false)));
        }
        return out;
    }

    /** {@code row} made to match {@code leader} in everything but wood, and locked. */
    private static VariantState follow(VariantState leader, VariantState row, boolean isLeader) {
        VariantState next = isLeader ? row : SignVariants.copyPlacement(leader, row);
        if (!isLeader) {
            SignVariants.Text text = SignVariants.text(leader.blockEntityNbt());
            next = next.withState(next.state(), SignVariants.withText(next.blockEntityNbt(), text));
        }
        return next.withSign(next.sign().withAuto(leader.sign().auto()).withLocked(true));
    }

    /**
     * {@code added} as it joins a cell holding {@code cellRows}: a sign added to a locked cell takes
     * everything but its wood from the lock; one added to a cell whose signs
     * share their text takes that text (and wax) and joins the sharing, so "same in cell" stays
     * true. Anything else comes back unchanged.
     */
    public static VariantState joinShared(List<VariantState> cellRows, VariantState added) {
        if (!isSignRow(added) || cellRows == null) return added;
        for (VariantState r : cellRows) {
            if (isSignRow(r) && r.sign().locked()) return follow(r, added, false);
        }
        for (VariantState r : cellRows) {
            if (isSignRow(r) && r.sign().shared()) {
                SignVariants.Text text = SignVariants.text(r.blockEntityNbt());
                return added.withState(added.state(), SignVariants.withText(added.blockEntityNbt(), text))
                    .withSign(added.sign().withShared(true));
            }
        }
        return added;
    }

    /**
     * {@code rows} with row {@code idx} mounted per the wire value: 0–3 a fixed
     * {@link SignVariants.Mount} (clearing Auto), {@link SignVariants#AUTO_WIRE} Auto. {@code null}
     * when the wood has no such block.
     */
    @Nullable
    public static List<VariantState> setMount(List<VariantState> rows, int idx, int wire) {
        return setMount(rows, idx, wire, true);
    }

    /**
     * {@link #setMount(List, int, int)}, refusing Auto when {@code autoAllowed} is false — a plot
     * whose placer never runs the post-overlay pass ({@link BlockVariantPlot#supportsConnectMode}),
     * where an Auto sign would quietly keep its stored mount.
     */
    @Nullable
    public static List<VariantState> setMount(List<VariantState> rows, int idx, int wire, boolean autoAllowed) {
        if (!valid(rows, idx)) return null;
        VariantState r = rows.get(idx);
        VariantState next;
        if (wire == SignVariants.AUTO_WIRE) {
            if (!autoAllowed) return null;
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
        if (r.sign().locked()) {
            for (int i = 0; i < out.size(); i++) {
                VariantState o = out.get(i);
                if (i == idx || !isSignRow(o) || !o.sign().locked()) continue;
                VariantState placed = SignVariants.copyPlacement(next, o);
                out.set(i, placed.withSign(placed.sign().withAuto(next.sign().auto())));
            }
        }
        return out;
    }

    private static boolean valid(List<VariantState> rows, int idx) {
        return rows != null && idx >= 0 && idx < rows.size() && isSignRow(rows.get(idx));
    }
}
