package games.brennan.dungeontrain.narrative.block;

import games.brennan.dungeontrain.advancement.GlobalNarrativeProgress;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.event.AchievementEvents;
import games.brennan.dungeontrain.narrative.BookFactory;
import games.brennan.dungeontrain.narrative.NarrativeBookEvents;
import games.brennan.dungeontrain.narrative.NarrativeBookTag;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.narrative.PlayerNarrativeBookTag;
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
 *   <li>If every story is complete for the world, the right-click no-ops
 *       and shows an action-bar message — and the lectern stays "empty"
 *       until more content loads.</li>
 *   <li>On break, drops a plain {@code minecraft:lectern} (its block loot
 *       table) plus the resolved book if one was opened — vanilla
 *       {@link LecternBlock#onRemove} pops the {@link LecternBlockEntity}'s
 *       stored book. An un-opened lectern (empty BE) drops only the lectern.</li>
 * </ul>
 *
 * <p>Reuses {@link LecternBlockEntity} via NeoForge's
 * {@link BlockEntityTypeAddBlocksEvent} (handled
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
            sp.displayClientMessage(
                Component.literal("All narratives complete.").withStyle(ChatFormatting.YELLOW),
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
        // meaning a one-and-done viewer never advances. For the same reason
        // we also record into the cross-world GlobalNarrativeProgress and
        // re-check the story advancements here, so a first-and-only read
        // still counts toward them.
        NarrativeBookTag.read(book).ifPresent(id -> {
            NarrativeProgressData data = NarrativeProgressData.get(overworld);
            data.markRead(id.storyBasename(), id.letterIndex());
            // Cross-world store is frozen for cheated runs; per-world `data` (the
            // login-absorption source + lectern selection) still records.
            boolean cheated = RunIntegrity.isCheated(sp);
            if (!cheated) {
                GlobalNarrativeProgress.markRead(id.storyBasename(), id.letterIndex());
            }
            if (id.variantIndex() >= 0) {
                // Mark the variant on BOTH stores, matching recordRead: keeps
                // the per-world data complete (it is the login absorption
                // source) and the global store correct for a one-and-done
                // lectern reader who never re-opens the book.
                data.markStoryVariantSeen(id.storyBasename(), id.letterIndex(), id.variantIndex());
                if (!cheated) {
                    GlobalNarrativeProgress.markVariantSeen(
                        id.storyBasename(), id.letterIndex(), id.variantIndex());
                }
            }
            AchievementEvents.notifyStoryProgress(sp);
            // First-and-only read of this lectern — count it toward the
            // death-screen books tally (deduped per run; page-turns / re-opens
            // route through NarrativeBookEvents.onRightClickBlock and no-op).
            NarrativeBookEvents.countLecternBookForRun(sp, id);
        });

        // Parallel branch for a served PLAYER narrative letter (exactly one of the two tags is present).
        // World-local only: advance the player-series read-set and count it toward the death-screen tally,
        // but intentionally NOT GlobalNarrativeProgress / story advancements — player series are non-canon.
        PlayerNarrativeBookTag.read(book).ifPresent(pid -> {
            NarrativeProgressData data = NarrativeProgressData.get(overworld);
            data.markPlayerLetterRead(pid.seriesId(), pid.letterIndex());
            NarrativeBookEvents.countPlayerSeriesLetterForRun(sp, pid);
        });

        // Now let vanilla open the lectern menu — it reads the BE's book
        // we just swapped in.
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    // Block removal is left to vanilla LecternBlock#onRemove: it pops the
    // stored book (popBook) and then clears the BE. Breaking a narrative
    // lectern therefore drops both the plain lectern (block loot table) and
    // the resolved book, if one was opened.
}
