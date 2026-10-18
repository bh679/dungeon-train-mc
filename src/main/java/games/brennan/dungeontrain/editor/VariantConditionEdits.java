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
    /** {@code toggle:<side>} flips one side; {@code others:<side>} flips every other side. */
    public static final String TOGGLE_PREFIX = "toggle:";
    public static final String OTHERS_PREFIX = "others:";
    /** Mode tokens: Touching (specific blocks on the sides) and Kind (Solid / Empty / Any on the sides). */
    public static final String MODE_TOUCHING = "mode:touching";
    public static final String MODE_KIND = "mode:kind";

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
        if (MODE_KIND.equals(token)) return Result.ok(current.toKindMode());
        if (MODE_TOUCHING.equals(token)) {
            VariantCondition plain = current.withoutKinds();
            return Result.ok(plain.isGroupTarget() ? plain.withDirection(VariantCondition.DEFAULT_DIRECTION) : plain);
        }
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
            return Result.ok(current.withoutKinds().withGroup(step));
        }
        if (token.startsWith(TOGGLE_PREFIX) || token.startsWith(OTHERS_PREFIX)) {
            boolean others = token.startsWith(OTHERS_PREFIX);
            net.minecraft.core.Direction side = net.minecraft.core.Direction.byName(
                token.substring(others ? OTHERS_PREFIX.length() : TOGGLE_PREFIX.length()));
            if (side == null) return Result.refused(null);
            return Result.ok(others ? current.toggleOtherSides(side) : current.toggleSide(side));
        }
        VariantCondition parsed = VariantCondition.parseTarget(token);
        if (parsed == null || parsed.isGroupTarget()) return Result.refused(null);
        return Result.ok(current.withSides(parsed.sideMask()));
    }

    /** Flip one value by id — Group mode's click on one of the group's blocks. */
    public static Result toggleValue(VariantCondition current, String rawId) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(rawId);
        if (id == null || !VariantCondition.isKnownValue(id)) return Result.refused(null);
        VariantCondition next = current.withValueToggled(id);
        if (next == current) return Result.refused("chat.dungeontrain.editor_bar.variant_menu.condition_full");
        // Unticking the last kind box would drop the row out of Kind mode; Clear is the way out.
        if (current.isKindMode() && !next.isKindMode()) return Result.refused(null);
        return Result.ok(next);
    }

    /**
     * The distinct block ids a group can roll, in list order — what Group mode offers as its
     * togglable values. Mobs and empty rows read as air, the block they leave behind.
     */
    public static java.util.List<net.minecraft.resources.ResourceLocation> groupValues(
            @Nullable java.util.List<VariantState> groupStates) {
        java.util.LinkedHashSet<net.minecraft.resources.ResourceLocation> out = new java.util.LinkedHashSet<>();
        if (groupStates != null) {
            for (VariantState s : groupStates) {
                if (s.isGroupRef()) continue;
                out.add(VariantCondition.idOf(s.state()));
            }
        }
        return java.util.List.copyOf(out);
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
