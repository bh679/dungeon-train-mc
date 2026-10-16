package games.brennan.dungeontrain.editor;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.TreeSet;

/**
 * The block-variant menu's condition edits, kept out of {@link BlockVariantMenuController} so the
 * rules live in one small place: what a target token means, what a held stack adds, and which
 * groups a row may point at.
 */
public final class VariantConditionEdits {

    /** Target token that steps to the next lock group in use (or the previous, with {@link #GROUP_PREV}). */
    public static final String GROUP_NEXT = "group+";
    public static final String GROUP_PREV = "group-";

    private VariantConditionEdits() {}

    /** Outcome of one edit: the new condition, or a translation key explaining a refusal. */
    public record Result(@Nullable VariantCondition condition, @Nullable String refusalKey) {
        static Result ok(VariantCondition c) { return new Result(c, null); }
        static Result refused(String key) { return new Result(null, key); }
    }

    /**
     * Retarget {@code current}. {@code token} is a direction name or {@link #GROUP_NEXT} /
     * {@link #GROUP_PREV}; a row may not point at its own cell's group, which would only ever see
     * its own roll.
     */
    public static Result setTarget(VariantCondition current, String token, Set<Integer> lockIdsInUse,
                                   int ownLockId) {
        if (GROUP_NEXT.equals(token) || GROUP_PREV.equals(token)) {
            TreeSet<Integer> groups = new TreeSet<>(lockIdsInUse);
            groups.remove(ownLockId);
            groups.remove(0);
            if (groups.isEmpty()) return Result.refused("chat.dungeontrain.editor_bar.variant_menu.condition_no_groups");
            boolean next = GROUP_NEXT.equals(token);
            Integer step = current.isGroupTarget()
                ? (next ? groups.higher(current.groupId()) : groups.lower(current.groupId()))
                : null;
            if (step == null) step = next ? groups.first() : groups.last();
            return Result.ok(current.withGroup(step));
        }
        VariantCondition parsed = VariantCondition.parseTarget(token);
        if (parsed == null || parsed.isGroupTarget()) return Result.refused(null);
        return Result.ok(current.withDirection(parsed.direction()));
    }

    /** Add the held block (empty hand = air) as a value. */
    public static Result addHeld(VariantCondition current, ItemStack held) {
        BlockState state;
        if (held.isEmpty()) {
            state = null;
        } else if (held.getItem() instanceof BlockItem blockItem) {
            state = blockItem.getBlock().defaultBlockState();
        } else {
            BlockState liquid = VariantLiquids.sourceStateFrom(held);
            if (liquid == null) return Result.refused("chat.dungeontrain.editor_bar.variant_menu.condition_hold_block");
            state = liquid;
        }
        var id = VariantCondition.valueFor(state);
        if (current.blocks().contains(id)) {
            return Result.refused("chat.dungeontrain.editor_bar.variant_menu.condition_duplicate");
        }
        if (current.blocks().size() >= VariantCondition.MAX_VALUES) {
            return Result.refused("chat.dungeontrain.editor_bar.variant_menu.condition_full");
        }
        return Result.ok(current.withValueAdded(id));
    }

    /** True when a group-targeted condition still has a group to look at. */
    public static boolean isLive(VariantCondition c, Set<Integer> lockIdsInUse, int ownLockId) {
        if (!c.isGroupTarget()) return true;
        return c.groupId() != ownLockId && lockIdsInUse.contains(c.groupId());
    }
}
