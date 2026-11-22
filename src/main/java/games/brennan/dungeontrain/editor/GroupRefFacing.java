package games.brennan.dungeontrain.editor;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A lock-group reference that pins a facing: "group 3, facing east".
 *
 * <p>A reference row's {@link VariantState#rotation} was always {@link VariantRotation#NONE}; the
 * reference took its rotation from whatever the group resolved to. A non-default rotation on the
 * row now pins that value's facing instead, which is what lets one Rot Free group sit in a cell
 * several times, once per facing. The rotation is a single-slot {@link VariantRotation.Mode#LOCK},
 * taken from the copied block's facing ({@link RotationApplier#lockToCurrent}).</p>
 *
 * <p>The row's placeholder state is the group's block already turned that way, so the menu icon
 * shows the facing and the editor mirror / plot rotation (which turn {@code rotation} against
 * {@code state}) turn the pin with the template.</p>
 */
public final class GroupRefFacing {

    private GroupRefFacing() {}

    /** True when {@code ref} is a reference whose row pins a facing. */
    public static boolean isPinned(VariantState ref) {
        return ref != null && ref.isGroupRef() && !ref.rotation().isDefault();
    }

    /**
     * {@code terminal} (the value a reference resolved to) with {@code ref}'s pinned facing, or
     * {@code terminal} itself when nothing is pinned or the block cannot take that facing — a group
     * mixing stairs and planks still places its planks. Never mutates: the memoised pick stays as
     * the group's own cells placed it.
     */
    public static VariantState apply(VariantState ref, VariantState terminal) {
        if (!isPinned(ref) || terminal == null || terminal.isMob() || terminal.isGroupRef()) return terminal;
        BlockState base = terminal.state();
        if (base == null || !RotationApplier.canRotate(base)) return terminal;
        if (RotationApplier.isCompass(base) != RotationApplier.isCompass(ref.state())) return terminal;
        if ((RotationApplier.validDirMask(base) & ref.rotation().dirMask()) == 0) return terminal;
        // Turn the state too, not just the rotation field: the editor preview and anything else that
        // sets state() straight into the world would otherwise show the group's own facing.
        BlockState turned = RotationApplier.applySlot(base, slotOf(ref));
        return terminal.withState(turned, terminal.blockEntityNbt()).withRotation(ref.rotation());
    }

    /**
     * The block a reference row shows: the first rotatable option of the group turned to
     * {@code rotation}, else the first option as it is. {@code options} must not be empty.
     */
    public static BlockState placeholder(List<VariantState> options, VariantRotation rotation) {
        BlockState first = options.get(0).state();
        if (rotation == null || rotation.isDefault()) return first;
        int slot = Integer.numberOfTrailingZeros(rotation.dirMask());
        for (VariantState o : options) {
            BlockState s = o.state();
            if (o.isMob() || o.isGroupRef() || s == null || !RotationApplier.canRotate(s)) continue;
            BlockState turned = RotationApplier.applySlot(s, slot);
            if (turned != s || (RotationApplier.validDirMask(s) & rotation.dirMask()) != 0) return turned;
        }
        return first;
    }

    /** True when {@code a} and {@code b} reference the same group at the same facing. */
    public static boolean sameRef(VariantState a, VariantState b) {
        return a.isGroupRef() && b.isGroupRef() && a.groupRef() == b.groupRef()
            && a.rotation().equals(b.rotation());
    }

    /**
     * Short word for {@code ref}'s pinned facing in the menu and action bar: a compass point
     * ("N", "NE") for heads, banners and signs, else the direction ("north", "up").
     */
    public static String facingLabel(VariantState ref) {
        int slot = slotOf(ref);
        if (slot < 0) return "";
        if (RotationApplier.isCompass(ref.state())) return RotationApplier.slotLabel(ref.state(), slot);
        return slot < net.minecraft.core.Direction.values().length
            ? net.minecraft.core.Direction.values()[slot].getName() : "?";
    }

    /** The pinned slot of {@code ref}, or {@code -1} when it pins nothing. */
    public static int slotOf(VariantState ref) {
        return isPinned(ref) ? Integer.numberOfTrailingZeros(ref.rotation().dirMask()) : -1;
    }
}
