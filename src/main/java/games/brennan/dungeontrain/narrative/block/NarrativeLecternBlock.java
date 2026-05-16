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
 * A vanilla-lectern look-alike that defers its book content to right-click
 * time and resolves it per-player from {@link BookFactory#buildOrRandomForPlayer}.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Always renders with a book on top (state's {@code HAS_BOOK=true} from
 *       placement).</li>
 *   <li>On right-click (server-side): looks up the player's "current book"
 *       via {@link BookFactory#buildOrRandomForPlayer}, swaps it into the
 *       vanilla {@link LecternBlockEntity} in this position, then delegates
 *       to {@link LecternBlock#useWithoutItem} so the screen opens with the
 *       freshly-resolved content.</li>
 *   <li>If every story is complete for the player, the right-click no-ops
 *       and shows an action-bar message.</li>
 *   <li>On break, drops only the lectern item — the rotating book is purely
 *       transient and shouldn't survive as loot.</li>
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
        ServerLevel overworld = sl.getServer().overworld();

        Optional<ItemStack> resolved = BookFactory.buildOrRandomForPlayer(overworld, sp, pos.asLong());
        if (resolved.isEmpty()) {
            sp.displayClientMessage(
                Component.literal("All narratives complete.").withStyle(ChatFormatting.YELLOW),
                /*actionBar*/ true);
            return InteractionResult.CONSUME;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof LecternBlockEntity lectern) {
            ItemStack book = resolved.get();
            book.setCount(1);
            lectern.setBook(book);
            lectern.setChanged();

            // Decide AND record the read atomically — opening this lectern
            // counts as reading the letter we just resolved. Without this,
            // the read-marking only fires on the NEXT right-click via the
            // RightClickBlock subscriber (which sees the previous BE
            // state), meaning a one-and-done viewer never advances.
            NarrativeBookTag.read(book).ifPresent(id ->
                NarrativeProgressData.get(overworld)
                    .markRead(sp.getUUID(), id.storyBasename(), id.letterIndex()));
        }
        // Now let vanilla open the lectern menu — it reads the BE's book
        // we just swapped in.
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    /**
     * Vanilla {@link LecternBlock#onRemove} drops the stored book as an
     * item. For narrative_lectern that book is transient (re-resolved every
     * click) — clear the BE's book before super so vanilla's drop logic has
     * nothing to scatter.
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
