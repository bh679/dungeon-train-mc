package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * A container standing in a portal corridor is <b>one</b> container, whichever of the pair's two
 * copies you are looking at.
 *
 * <p>{@link PortalEditMirror} keeps a corridor and its twin block-for-block identical, but only as
 * <i>block state</i> — a chest mirrors as a chest, not as its contents. For every other block in a
 * corridor that is right. For a container it is a hole big enough to eat a full shulker box:</p>
 *
 * <ul>
 *   <li>The mirror cannot carry contents even in principle. Both of its feeds fire during
 *       {@code Level.setBlock}, before {@code BlockItem.updateCustomBlockEntityTag} writes the
 *       shulker's {@code CONTAINER} component into the block entity, so the mirrored copy is empty
 *       no matter what.</li>
 *   <li>A player cannot tell which copy they are standing at — that is the whole point of the
 *       crossing — so half the time they open the empty one.</li>
 *   <li>Mining the empty one drops an empty shulker, and the mirror then clears the full copy
 *       through {@code SilentBlockOps.clearBlockSilent}, which drops the block entity without
 *       spilling. The contents are gone.</li>
 *   <li>And the other way round is a duplicate: empty it on one side, cross the midpoint, and the
 *       untouched copy still holds everything.</li>
 * </ul>
 *
 * <p><b>One authoritative block entity, opened from both sides.</b> The carriage-side copy is
 * canonical — arbitrary, but stable: it is the side Sable's hook already feeds, and the side a
 * hopper riding the train can reach. A click on the twin copy is redirected to it by
 * {@code event.PortalContainerEvents}, so two players standing one in each dimension end up in the
 * same container and see each other's changes live, exactly as two players at one double chest do.
 * Moving the contents to whoever touched a copy last was the obvious alternative and fails precisely
 * there: the second player to open would pull the shulker out from under the first.</p>
 *
 * <p><b>What this class owns</b> is the pairing and the item movement: resolving a position to its
 * partner cell on either side ({@link #linkOf}), and pouring one copy into the other
 * ({@link #gatherInto}) so a break drops the real contents and leaves the partner empty — the
 * mirror's silent clear then makes "the other one is disabled" true rather than lossy.</p>
 *
 * <p>Moving items changes no block state, so nothing here re-enters the mirror and no re-entrancy
 * guard is needed — unlike every write {@link PortalEditMirror} makes.</p>
 *
 * <p><b>What is deliberately not handled.</b> A container blown up or pushed by a piston still loses
 * the partner's contents: there is no player intent to gather on, and it is no worse than today. A
 * hopper feeding the twin copy sees an empty container, because the contents live on the carriage
 * side. And anything left in a corridor is lost when the corridor is re-stamped or the twin is
 * cleared as the train rolls away, which is true of every block in a corridor and not this class's
 * problem.</p>
 */
public final class PortalContainerLink {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalContainerLink() {}

    /**
     * One cell of a corridor, and the same cell in the pair's other copy.
     *
     * @param here          the position asked about
     * @param partner       the matching cell in the other copy
     * @param canonicalHere true when {@code here} is the carriage-side (sub-level plot) copy — the
     *                      one that actually holds the contents
     */
    public record Cell(BlockPos here, BlockPos partner, boolean canonicalHere) {

        /** The copy that holds the contents. */
        public BlockPos canonical() {
            return canonicalHere ? here : partner;
        }

        /** The copy that is only a shell — a shulker-shaped door onto {@link #canonical()}. */
        public BlockPos shell() {
            return canonicalHere ? partner : here;
        }
    }

    /**
     * The pairing for {@code pos}, from whichever side it is on, or {@code null} if it is not in a
     * live portal corridor.
     *
     * <p>Sits on the right-click path, so it bails on the same empty-map check
     * {@link PortalEditMirror} opens with before doing any coordinate work.</p>
     *
     * <p>Both sides are addressable through the one {@link ServerLevel}: a carriage's blocks live in
     * a Sable sub-level plot at shipyard coordinates, which is already how {@link PortalEditMirror}
     * reads and writes them. The carriage-side resolution — sub-level container → plot — is the same
     * idiom {@code mixin/SableBlockChangeGuardMixin} and
     * {@code event.SharedCarriageAdvancementEvents.resolveCarriage} use.</p>
     */
    @Nullable
    public static Cell linkOf(ServerLevel level, BlockPos pos) {
        if (PortalPairIndex.isEmpty()) return null;

        // Twin first: an ordinary world position, one bounds check per live pair and no plot lookup.
        PortalPairIndex.Entry twinEntry = PortalPairIndex.findByTwinPos(pos);
        if (twinEntry != null) {
            int[] local = twinEntry.localOfTwin(pos);
            if (local != null) {
                return new Cell(pos.immutable(), twinEntry.plotPosOf(local), false);
            }
        }

        LevelPlot plot = plotAt(level, pos);
        if (plot == null) return null;
        PortalPairIndex.Entry entry =
            PortalPairIndex.findByPlotPos(plot, pos.getX(), pos.getY(), pos.getZ());
        if (entry == null) return null;
        int[] local = entry.localOfPlot(pos.getX(), pos.getY(), pos.getZ());
        if (local == null) return null;
        return new Cell(pos.immutable(), entry.twinPosOf(local), true);
    }

    /** The sub-level plot owning {@code pos}, or {@code null} for an ordinary world position. */
    @Nullable
    private static LevelPlot plotAt(ServerLevel level, BlockPos pos) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        ChunkPos cpos = new ChunkPos(pos);
        if (container.getChunkHolder(cpos) == null) return null;
        return container.getPlot(cpos);
    }

    /** The container block entity at {@code pos}, or {@code null} if there isn't one. */
    @Nullable
    public static Container containerAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container container ? container : null;
    }

    /**
     * Pour {@code partner}'s contents into {@code target}, leaving the partner empty.
     *
     * <p>Called at the two moments the pair's two block entities must stop disagreeing: just before
     * one of them is <b>broken</b> (so vanilla's drop reads the real contents, and the copy the
     * mirror is about to clear has nothing left to lose), and just before the canonical copy is
     * <b>opened</b> (which is what normalises a full shulker placed on the twin side, whose contents
     * landed locally because placement runs after the mirror).</p>
     *
     * <p>Anything that will not fit is dropped at {@code target} rather than deleted. That only
     * happens if both copies were independently filled, which takes two players in two dimensions
     * doing it at once — rare enough not to design around, common enough not to eat someone's
     * diamonds over.</p>
     */
    public static void gatherInto(ServerLevel level, BlockPos target, BlockPos partner) {
        Container to = containerAt(level, target);
        Container from = containerAt(level, partner);
        if (to == null || from == null || to == from) return;
        if (isEmpty(from)) return;

        List<ItemStack> leftovers = consolidate(from, to);
        for (ItemStack stack : leftovers) {
            Containers.dropItemStack(level, target.getX() + 0.5, target.getY() + 0.5,
                target.getZ() + 0.5, stack);
        }
        LOGGER.debug("[DungeonTrain] Portal container gathered {} → {}{}", partner, target,
            leftovers.isEmpty() ? "" : " (" + leftovers.size() + " stack(s) spilled: both copies held items)");
    }

    /** True if every slot is empty. */
    public static boolean isEmpty(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    /**
     * Move everything from {@code from} into {@code to}, returning whatever did not fit.
     *
     * <p>Split out from {@link #gatherInto} with no level in sight so it can be unit-tested — the
     * stack merging is where the arithmetic is, not the plumbing. Same division as
     * {@link PortalSever#isSeveringBreak}.</p>
     *
     * <p>Insertion respects {@code canPlaceItem}, which is what stops a shulker box being nested
     * inside another one on the way across; a rejected stack comes back in the leftovers rather than
     * being forced in.</p>
     *
     * @return the stacks that did not fit, which the caller must not drop on the floor (figuratively)
     */
    public static List<ItemStack> consolidate(Container from, Container to) {
        List<ItemStack> leftovers = new ArrayList<>();
        boolean moved = false;

        for (int slot = 0; slot < from.getContainerSize(); slot++) {
            ItemStack stack = from.getItem(slot);
            if (stack.isEmpty()) continue;

            from.setItem(slot, ItemStack.EMPTY);
            moved = true;

            ItemStack remainder = insert(to, stack);
            if (!remainder.isEmpty()) leftovers.add(remainder);
        }

        if (moved) {
            from.setChanged();
            to.setChanged();
        }
        return leftovers;
    }

    /**
     * Put {@code stack} into {@code to} — topping up matching stacks first, then filling empty slots
     * — and return what is left of it.
     *
     * <p>Mutates and returns {@code stack} itself, so an exhausted stack comes back
     * {@link ItemStack#isEmpty() empty} rather than as a fresh object.</p>
     */
    private static ItemStack insert(Container to, ItemStack stack) {
        int size = to.getContainerSize();

        for (int slot = 0; slot < size && !stack.isEmpty(); slot++) {
            ItemStack dest = to.getItem(slot);
            if (dest.isEmpty() || !ItemStack.isSameItemSameComponents(dest, stack)) continue;
            int room = limitFor(to, dest) - dest.getCount();
            if (room <= 0) continue;
            int move = Math.min(room, stack.getCount());
            dest.grow(move);
            stack.shrink(move);
            to.setItem(slot, dest);
        }

        for (int slot = 0; slot < size && !stack.isEmpty(); slot++) {
            if (!to.getItem(slot).isEmpty()) continue;
            if (!to.canPlaceItem(slot, stack)) continue;
            to.setItem(slot, stack.split(limitFor(to, stack)));
        }

        return stack;
    }

    /** How much of {@code stack} one slot of {@code to} may hold — the container's cap or the item's. */
    private static int limitFor(Container to, ItemStack stack) {
        return Math.min(to.getMaxStackSize(), stack.getMaxStackSize());
    }
}
