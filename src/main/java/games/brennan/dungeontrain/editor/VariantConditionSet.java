package games.brennan.dungeontrain.editor;

import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A row's If list: the {@link VariantCondition}s it is gated on, combined with AND ({@code any}
 * false, the default) or OR. The row's Level band ({@link VariantDifficulty}) joins the same
 * combination at roll time but lives on {@link VariantState#difficulty} — see
 * {@link VariantConditions#eligible}.
 *
 * <p>A condition with no values is kept (an author picks its target before its first value) but
 * never gates; the set {@link #isDefault is default} when none of its conditions gate.</p>
 *
 * <p>Token: each condition's {@link VariantCondition#id token} joined by {@code ;}, prefixed
 * {@code any|} for OR. One condition's token is exactly that condition's token, so every sidecar
 * written before the list existed reads and re-writes unchanged.</p>
 */
public record VariantConditionSet(List<VariantCondition> conditions, boolean any) {

    public static final VariantConditionSet NONE = new VariantConditionSet(List.of(), false);
    /** Keeps the token under the sync packet's cap and the popup's chip row legible. */
    public static final int MAX_CONDITIONS = 6;

    private static final String SEPARATOR = ";";
    private static final String ANY_PREFIX = "any|";

    public VariantConditionSet {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (conditions.size() > MAX_CONDITIONS) conditions = conditions.subList(0, MAX_CONDITIONS);
    }

    public static VariantConditionSet of(VariantCondition single) {
        return single == null ? NONE : new VariantConditionSet(List.of(single), false);
    }

    /** True when no condition gates — every condition is empty, or there are none. */
    public boolean isDefault() {
        for (VariantCondition c : conditions) if (!c.isDefault()) return false;
        return true;
    }

    public int size() {
        return conditions.size();
    }

    /** The conditions that gate (have values), in order. */
    public List<VariantCondition> gating() {
        List<VariantCondition> out = new ArrayList<>(conditions.size());
        for (VariantCondition c : conditions) if (!c.isDefault()) out.add(c);
        return out;
    }

    /** The condition at {@code index}, or a fresh {@link VariantCondition#NONE} past the end. */
    public VariantCondition get(int index) {
        return index >= 0 && index < conditions.size() ? conditions.get(index) : VariantCondition.NONE;
    }

    /** Copy with the condition at {@code index} replaced; at {@link #size} it is appended. */
    public VariantConditionSet with(int index, VariantCondition c) {
        if (index < 0 || index > conditions.size() || c == null) return this;
        List<VariantCondition> out = new ArrayList<>(conditions);
        if (index == out.size()) {
            if (out.size() >= MAX_CONDITIONS) return this;
            out.add(c);
        } else {
            out.set(index, c);
        }
        return new VariantConditionSet(out, any);
    }

    /** Copy with {@code c} appended; unchanged when the list is full. */
    public VariantConditionSet withAdded(VariantCondition c) {
        return with(conditions.size(), c);
    }

    /** Copy without the condition at {@code index}; unchanged when out of range. */
    public VariantConditionSet withRemoved(int index) {
        if (index < 0 || index >= conditions.size()) return this;
        List<VariantCondition> out = new ArrayList<>(conditions);
        out.remove(index);
        return new VariantConditionSet(out, any);
    }

    public VariantConditionSet withAny(boolean newAny) {
        return newAny == any ? this : new VariantConditionSet(conditions, newAny);
    }

    /** True when any condition targets a lock group. */
    public boolean hasGroupTarget() {
        for (VariantCondition c : conditions) if (c.isGroupTarget()) return true;
        return false;
    }

    public VariantConditionSet rotated(Rotation rotation) {
        return map(c -> c.rotated(rotation));
    }

    public VariantConditionSet reflected(boolean flipX, boolean flipY, boolean flipZ) {
        return map(c -> c.reflected(flipX, flipY, flipZ));
    }

    private VariantConditionSet map(java.util.function.UnaryOperator<VariantCondition> f) {
        boolean changed = false;
        List<VariantCondition> out = new ArrayList<>(conditions.size());
        for (VariantCondition c : conditions) {
            VariantCondition next = f.apply(c);
            changed |= next != c;
            out.add(next);
        }
        return changed ? new VariantConditionSet(out, any) : this;
    }

    /** Sync / NBT token, empty conditions included so the editor keeps a target the author picked. */
    public String id() {
        StringBuilder sb = new StringBuilder(any ? ANY_PREFIX : "");
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) sb.append(SEPARATOR);
            sb.append(conditions.get(i).id());
        }
        return sb.toString();
    }

    /** Sidecar token: only the gating conditions; empty when nothing gates. */
    public String storedId() {
        List<VariantCondition> live = gating();
        if (live.isEmpty()) return "";
        return new VariantConditionSet(live, any && live.size() > 1).id();
    }

    /** Parse a token; a malformed condition becomes {@link VariantCondition#NONE} in its slot. */
    public static VariantConditionSet parse(@Nullable String token) {
        if (token == null) return NONE;
        String raw = token.trim();
        boolean any = raw.startsWith(ANY_PREFIX);
        if (any) raw = raw.substring(ANY_PREFIX.length());
        if (raw.isEmpty()) return any ? NONE.withAny(true) : NONE;
        List<VariantCondition> out = new ArrayList<>();
        for (String part : raw.split(SEPARATOR, -1)) out.add(VariantCondition.parse(part));
        return new VariantConditionSet(out, any);
    }
}
