package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.narrative.PlayerBookPendingTag;
import games.brennan.dungeontrain.narrative.PortalBookLockTag;
import games.brennan.dungeontrain.narrative.RandomBookTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

/**
 * Marks every book inside a freshly stamped room as belonging to that room's author.
 *
 * <h2>Why a sweep rather than a flag threaded into the roll</h2>
 * <p>{@code ContainerContentsRoller} is the loot path for every carriage on the train. Threading a
 * portal-room setting down through {@code CarriageContentsPlacer} into it would put portal state on
 * the main loot path for a case that only portal rooms have — and the roller is where the shared-book
 * taper, the archaeology loot and the enchanted-book rolls all live. Sweeping the room's containers
 * afterwards keeps the change entirely inside {@code portal/}, so an ordinary carriage's loot is
 * provably untouched.</p>
 *
 * <p>It also catches books the roller never saw: a room's own {@code .nbt} may have shelves stamped
 * straight into it, with no contents template involved at all — and a hand-authored library is exactly
 * the room most likely to want this setting.</p>
 *
 * <h2>What it touches</h2>
 * <p>Only DT's own placeholder books, inside the room's own containers: a stack already awaiting the
 * community-pool upgrade ({@link PlayerBookPendingTag}), or a built-in random book
 * ({@link RandomBookTag}) which is marked pending here so it joins the lock. That is what makes the
 * setting cover every book in the room rather than only the community-book slots — inside a locked
 * room the read-progress taper that normally decides between the two is bypassed.</p>
 *
 * <p>Nothing in a player's inventory is ever reachable from here, so a book carried INTO a locked room
 * cannot be rewritten. An ordinary written book somebody left in a chest is untouched too — it carries
 * neither marker.</p>
 */
public final class PortalRoomBookLockStamper {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalRoomBookLockStamper() {}

    /**
     * Stamp {@code mode} onto every eligible book in the box at {@code origin} of {@code size}.
     *
     * <p>No-op for an unlocked room, which is the overwhelming majority — the gate is first so a room
     * that never uses this pays one enum comparison per stamp.</p>
     */
    public static void stampRoom(ServerLevel level, BlockPos origin, Vec3i size, PortalRoomBooks mode) {
        if (level == null || origin == null || size == null || mode == null || !mode.locks()) return;
        int marked = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    // Ask the state before the block entity: getBlockEntity on a plain block still
                    // walks the chunk's map, and a room is a few hundred positions of mostly air.
                    if (!level.getBlockState(cursor).hasBlockEntity()) continue;
                    BlockEntity be = level.getBlockEntity(cursor);
                    if (!(be instanceof Container container)) continue;
                    if (stampContainer(container, mode)) {
                        be.setChanged();
                        marked++;
                    }
                }
            }
        }
        if (marked > 0) {
            LOGGER.debug("[DungeonTrain] Portal room author lock ({}) marked books in {} container(s)",
                mode.id(), marked);
        }
    }

    /** Stamp every eligible stack in one container; true when it changed. */
    private static boolean stampContainer(Container container, PortalRoomBooks mode) {
        boolean changed = false;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!isLockable(stack)) continue;
            // Pending as well as locked: the lock is a modifier on the pending upgrade, and a
            // built-in random book has no pending marker of its own until this puts one on it.
            PlayerBookPendingTag.markPending(stack);
            PortalBookLockTag.stamp(stack, mode);
            changed = true;
        }
        return changed;
    }

    /**
     * True for a DT placeholder book: one already awaiting the community upgrade, or a built-in random
     * book. Anything else in the container — an ordinary written book, a player's own signed copy, a
     * community book already resolved — is left exactly as it is.
     */
    private static boolean isLockable(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return false;
        if (PortalBookLockTag.isLocked(stack)) return false;   // idempotent: a re-stamp changes nothing
        return PlayerBookPendingTag.isPending(stack) || RandomBookTag.read(stack).isPresent();
    }
}
