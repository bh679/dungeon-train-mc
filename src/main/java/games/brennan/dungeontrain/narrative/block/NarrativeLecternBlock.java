package games.brennan.dungeontrain.narrative.block;

import games.brennan.dungeontrain.narrative.BookFactory;
import games.brennan.dungeontrain.narrative.NarrativeBookTag;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A vanilla-lectern look-alike that resolves its book content on the FIRST
 * right-click and then locks it for the lifetime of the block.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Always renders with a book on top (state's {@code HAS_BOOK=true} from
 *       placement).</li>
 *   <li>On right-click (server-side):
 *     <ul>
 *       <li>If the {@link LecternBlockEntity} already holds a non-empty book
 *           (i.e. a prior click already resolved one), skip resolution and
 *           delegate straight to {@link LecternBlock#useWithoutItem}. The
 *           locked book opens unchanged.</li>
 *       <li>If the BE is empty, ask {@link BookFactory#buildOrRandomForLectern}
 *           for the next book in the world's narrative cursor, place it on
 *           the BE (vanilla serialization persists the stack across saves),
 *           advance world progress, then delegate to vanilla.</li>
 *     </ul>
 *   </li>
 *   <li>When every story is complete for the world, {@link BookFactory}
 *       silently cycles per-story progress and picks a fresh book from the
 *       full registry minus the recently-started cooldown queue — lecterns
 *       keep working past completion, no "all complete" stall.</li>
 *   <li>On break, drops only the lectern item — the rotating book is
 *       transient w.r.t. block loot (the BE is destroyed with the block,
 *       so the lock dies with the lectern).</li>
 * </ul>
 *
 * <p>Reuses {@link LecternBlockEntity} via NeoForge's
 * {@link net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent} (handled
 * in {@link NarrativeLecternHooks}). No custom BE / renderer required.</p>
 */
public class NarrativeLecternBlock extends LecternBlock {

    public NarrativeLecternBlock(Properties properties) {
        super(properties);
    }

    /**
     * Force {@code HAS_BOOK=true} so the visible book always appears even
     * before any player has clicked. Vanilla's getStateForPlacement leaves
     * it false (lectern starts empty); we override to start true.
     */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState base = super.getStateForPlacement(context);
        if (base == null) return null;
        return base.setValue(LecternBlock.HAS_BOOK, true);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel sl)) return InteractionResult.PASS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof LecternBlockEntity lectern)) {
            return super.useWithoutItem(state, level, pos, player, hit);
        }

        // Lock check: a prior interaction already resolved a book for this
        // lectern. The BE persists it across saves, so just open it again.
        if (!lectern.getBook().isEmpty()) {
            return super.useWithoutItem(state, level, pos, player, hit);
        }

        // First interaction — resolve the world's next book and lock it.
        ServerLevel overworld = sl.getServer().overworld();
        Optional<ItemStack> resolved = BookFactory.buildOrRandomForLectern(overworld, pos.asLong());
        if (resolved.isEmpty()) {
            // Reachable only when zero stories are registered (packaging
            // fault). The silent-cycle fallback in BookFactory keeps lecterns
            // working past completion, so this is no longer a normal
            // end-of-content state.
            sp.displayClientMessage(
                Component.literal("No narratives available.").withStyle(ChatFormatting.YELLOW),
                /*actionBar*/ true);
            return InteractionResult.CONSUME;
        }

        ItemStack book = resolved.get();
        book.setCount(1);
        lectern.setBook(book);
        lectern.setChanged();

        // Decide AND record the read atomically — opening this lectern
        // counts as reading the letter we just resolved. Without this,
        // the read-marking only fires on the NEXT right-click via the
        // RightClickBlock subscriber (which sees the previous BE state),
        // meaning a one-and-done viewer never advances.
        NarrativeBookTag.read(book).ifPresent(id ->
            NarrativeProgressData.get(overworld)
                .markRead(id.storyBasename(), id.letterIndex()));

        // Now let vanilla open the lectern menu — it reads the BE's book
        // we just swapped in.
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    /**
     * Vanilla {@link LecternBlock#onRemove} drops the stored book as an
     * item. The lock dies with the lectern — clear the BE's book before
     * super so vanilla's drop logic has nothing to scatter.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof LecternBlockEntity lectern) {
                lectern.setBook(ItemStack.EMPTY);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
