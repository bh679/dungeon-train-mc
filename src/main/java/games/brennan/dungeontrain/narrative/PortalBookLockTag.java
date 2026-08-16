package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.portal.PortalRoomBooks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Per-stack "this book came out of a room locked to one author" marker, stamped via
 * {@link DataComponents#CUSTOM_DATA}.
 *
 * <p>Carries the room's {@link PortalRoomBooks} value — how the author is chosen — and NOT the author
 * itself. Which person that resolves to is decided at pickup and memoised per room, because at stamp
 * time the relay's author directory may not have warmed yet (the same cold start
 * {@link PlayerBookPendingTag} exists for), and because {@link PortalRoomBooks#SELF} has no answer
 * until somebody picks the book up.</p>
 *
 * <p>Always accompanied by {@link PlayerBookPendingTag}: the lock is a modifier on the pending
 * upgrade, not a second mechanism. A stack carrying this but no pending marker has already been
 * resolved and is left alone.</p>
 *
 * <p>Fresh key prefix ({@code dt_playerbook_author_lock}) so it cannot collide with the
 * {@code dt_random_book*}, {@code dt_shared_book*} or {@code dt_playerbook_pending} keys.</p>
 */
public final class PortalBookLockTag {

    /** The room's author-lock mode, stored as its {@link PortalRoomBooks#id()}. */
    public static final String NBT_LOCK = "dt_playerbook_author_lock";

    private PortalBookLockTag() {}

    /** Stamp {@code mode} onto {@code stack}. No-op on empty/null stacks or an unlocked mode. */
    public static void stamp(ItemStack stack, PortalRoomBooks mode) {
        if (stack == null || stack.isEmpty() || mode == null || !mode.locks()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(NBT_LOCK, mode.id()));
    }

    /**
     * The lock on {@code stack}, or {@link PortalRoomBooks#OFF} when it carries none.
     *
     * <p>Reads through {@link PortalRoomBooks#parse}, which is total — a stack whose tag was hand-edited
     * to something unrecognised resolves as an ordinary book rather than throwing on a hot pickup path.</p>
     */
    public static PortalRoomBooks of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return PortalRoomBooks.OFF;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return PortalRoomBooks.OFF;
        CompoundTag tag = cd.copyTag();
        if (!tag.contains(NBT_LOCK, Tag.TAG_STRING)) return PortalRoomBooks.OFF;
        return PortalRoomBooks.parse(tag.getString(NBT_LOCK));
    }

    /** True when {@code stack} came out of a room that locks its books to an author. */
    public static boolean isLocked(ItemStack stack) {
        return of(stack).locks();
    }

    /** Remove the marker — after a resolution, successful or abandoned. No-op on empty/null stacks. */
    public static void clear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(NBT_LOCK));
    }
}
