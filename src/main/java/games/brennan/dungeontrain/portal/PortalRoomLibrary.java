package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.ChiseledBookshelfSync;
import games.brennan.dungeontrain.narrative.PortalLibraryTribute;
import games.brennan.dungeontrain.narrative.SharedBookFoundTag;
import games.brennan.dungeontrain.narrative.SharedBookPool;
import games.brennan.dungeontrain.narrative.SharedBookReadTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Deals one author's catalogue across a room's chiseled bookshelves.
 *
 * <h2>Why the shelves rather than the books</h2>
 * <p>A library room is <i>built</i> out of chiseled bookshelves — the shipped {@code
 * library_dimension} has 277 of them, about sixteen hundred slots. Marking individual book stacks
 * and resolving them at pickup, which is how this started, could never say the thing the room is for:
 * an author's twelve books standing in a hall of empty shelves. Filling the shelves says it at a
 * glance, before anybody has picked anything up.</p>
 *
 * <p>Only chiseled bookshelves. Chests, barrels and pots in the same room roll their ordinary loot —
 * a library whose chests are still chests.</p>
 *
 * <h2>Reproducible, not re-rolled</h2>
 * <p>Shelves are walked in reading order and the catalogue is dealt into them from a seed derived
 * from the pair key, so a copy that retires and is re-stamped as the tiling window slides — or a
 * whole structure re-stamped after the train drifts — puts the same books back in the same slots.
 * That is the same promise {@code PortalRoomCopies} makes about a room's variants, and for the same
 * reason: a sliding window must not be a loot machine.</p>
 */
public final class PortalRoomLibrary {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalRoomLibrary() {}

    /**
     * Fill the room at {@code origin} of {@code size} from {@code catalogue}.
     *
     * <p>Returns the number of books actually placed. An empty catalogue places nothing and leaves
     * every shelf as the template authored it, which is exactly what a room whose author could not be
     * resolved should look like.</p>
     */
    public static int stock(ServerLevel level, BlockPos origin, Vec3i size,
                            List<SharedBookPool.PoolBook> catalogue, int pairKey, String authorName) {
        if (level == null || origin == null || size == null || catalogue == null || catalogue.isEmpty()) {
            return 0;
        }
        // The note that says what the room is, before any shelf does. Placed even when the catalogue
        // is short — a room with two books on its shelves is exactly the room the note explains.
        dressLecterns(level, origin, size, authorName, pairKey);
        List<BlockPos> shelves = shelvesIn(level, origin, size);
        if (shelves.isEmpty()) return 0;

        List<SharedBookPool.PoolBook> order = dealOrder(catalogue, pairKey);
        int placed = 0;
        for (BlockPos shelf : shelves) {
            if (placed >= order.size()) break;
            if (!(level.getBlockEntity(shelf) instanceof ChiseledBookShelfBlockEntity be)) continue;
            boolean touched = false;
            for (int slot = 0; slot < be.getContainerSize() && placed < order.size(); slot++) {
                // Never over an authored book: a template that already put something on this shelf
                // said so deliberately, and the catalogue fills what is free around it.
                if (!be.getItem(slot).isEmpty()) continue;
                be.setItem(slot, bookStack(order.get(placed++)));
                touched = true;
            }
            if (touched) {
                be.setChanged();
                // Writing into the block entity does NOT recompute the slot-occupied blockstate —
                // vanilla only does that on the player-interaction path — so without this a filled
                // shelf keeps looking empty, which is the one thing this feature cannot afford.
                ChiseledBookshelfSync.syncIfNeeded(level, shelf);
            }
        }
        LOGGER.info("[DungeonTrain] Portal room {} stocked {} of {} book(s) across {} shelves",
            pairKey, placed, order.size(), shelves.size());
        return placed;
    }

    /**
     * Put the tribute note on every lectern in the room.
     *
     * <p>A lectern is where a library keeps its explanation, and the shelves cannot give one: a
     * player has to open two books and notice the same name twice before the pattern is visible at
     * all. One lectern says it outright.</p>
     *
     * <p>A lectern the template already stood a book on is left alone — an author who put something
     * there meant it, and this note is not more important than what they wrote.</p>
     */
    private static void dressLecterns(ServerLevel level, BlockPos origin, Vec3i size,
                                      String authorName, int pairKey) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < size.getY(); dy++) {
            for (int dz = 0; dz < size.getZ(); dz++) {
                for (int dx = 0; dx < size.getX(); dx++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.getBlockState(cursor).hasBlockEntity()) continue;
                    if (!(level.getBlockEntity(cursor) instanceof LecternBlockEntity lectern)) continue;
                    if (lectern.hasBook()) continue;
                    lectern.setBook(PortalLibraryTribute.buildStack(authorName, pairKey));
                    lectern.setChanged();
                    // Vanilla only flips HAS_BOOK from the player-interaction path, exactly like the
                    // chiseled bookshelf's slot-occupied properties — so an undressed lectern would
                    // hold a book nobody could see or open.
                    BlockState state = level.getBlockState(cursor);
                    if (state.hasProperty(LecternBlock.HAS_BOOK)) {
                        level.setBlock(cursor, state.setValue(LecternBlock.HAS_BOOK, true), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    /** True when this room has anywhere to put an author's books at all. */
    public static boolean hasShelves(ServerLevel level, BlockPos origin, Vec3i size) {
        return !shelvesIn(level, origin, size).isEmpty();
    }

    /**
     * Every chiseled bookshelf in the box, in reading order (y, then z, then x).
     *
     * <p>The order is the whole point: it is what makes a re-stamp deal the same book into the same
     * slot. Bottom-up so a short catalogue fills the shelves a player is looking at rather than the
     * ones above their head.</p>
     */
    private static List<BlockPos> shelvesIn(ServerLevel level, BlockPos origin, Vec3i size) {
        List<BlockPos> out = new ArrayList<>();
        if (level == null || origin == null || size == null) return out;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < size.getY(); dy++) {
            for (int dz = 0; dz < size.getZ(); dz++) {
                for (int dx = 0; dx < size.getX(); dx++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    // Ask the state first: getBlockEntity walks the chunk's map, and a room is a few
                    // hundred positions of mostly air.
                    if (!level.getBlockState(cursor).hasBlockEntity()) continue;
                    BlockEntity be = level.getBlockEntity(cursor);
                    if (be instanceof ChiseledBookShelfBlockEntity) out.add(cursor.immutable());
                }
            }
        }
        return out;
    }

    /**
     * The catalogue in the order this room deals it.
     *
     * <p>Shuffled per pair so two rooms serving the same author do not read as the same shelf, and
     * seeded rather than random so one room re-stamps to itself. A pure Fisher–Yates over a copy —
     * the catalogue belongs to {@code AuthorBookPool} and is shared.</p>
     */
    private static List<SharedBookPool.PoolBook> dealOrder(List<SharedBookPool.PoolBook> catalogue,
                                                           int pairKey) {
        List<SharedBookPool.PoolBook> order = new ArrayList<>(catalogue);
        long state = pairKey * 0x9E3779B97F4A7C15L + 0x5348454C46L; // "SHELF"
        for (int i = order.size() - 1; i > 0; i--) {
            state ^= state >>> 33;
            state *= 0xFF51AFD7ED558CCDL;
            state ^= state >>> 33;
            int j = (int) Math.floorMod(state, i + 1L);
            SharedBookPool.PoolBook tmp = order.get(i);
            order.set(i, order.get(j));
            order.set(j, tmp);
        }
        return order;
    }

    /**
     * One shelf-ready copy of a community book.
     *
     * <p>Stamped with the same markers the loot path puts on a community book, so burn-after-reading,
     * read tracking and the author's own "a familiar book…" line all recognise a book taken off a
     * library shelf as the community book it is.</p>
     */
    private static ItemStack bookStack(SharedBookPool.PoolBook book) {
        ItemStack stack = SharedBookPool.buildStack(book);
        SharedBookFoundTag.stamp(stack);
        SharedBookReadTag.stampId(stack, book.id());
        return stack;
    }
}
