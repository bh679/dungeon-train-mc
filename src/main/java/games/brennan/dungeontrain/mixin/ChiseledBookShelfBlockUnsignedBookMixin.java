package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.narrative.UnsignedBookShelfNotice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices a player shelving a book &amp; quill they wrote in but never signed, so
 * {@link UnsignedBookShelfNotice} can say the train pays it no attention.
 *
 * <p>Target: {@code ChiseledBookShelfBlock#addBook(Level, BlockPos, Player, ChiseledBookShelfBlockEntity,
 * ItemStack, int)} (1.21.1 Mojmap) — private and static, reached only from vanilla's {@code useItemOn} once it
 * has resolved which shelf slot the player hit and found it empty.</p>
 *
 * <p>That resolution is why this is a mixin rather than an event handler. The one event on the path,
 * {@code PlayerInteractEvent.RightClickBlock}, fires before vanilla decides whether the click puts a book in or
 * takes one out, and the geometry that decides it ({@code getHitSlot}) is private too — so a handler would have
 * to duplicate vanilla's slot math and still guess the outcome. At this injection point the answer is already
 * known, the stack is whole (vanilla shrinks it a line later), and nothing but a player interaction arrives:
 * hoppers and the mod's own shelf writers ({@code ChiseledBookshelfSync} callers) never reach it.</p>
 *
 * <p>Read-only and fail-open — the notice is wrapped no-throw so a fault here can never stop a book going onto a
 * shelf.</p>
 */
@Mixin(ChiseledBookShelfBlock.class)
public abstract class ChiseledBookShelfBlockUnsignedBookMixin {

    private static final Logger DUNGEONTRAIN$LOGGER = LogUtils.getLogger();

    @Inject(method = "addBook", at = @At("HEAD"))
    private static void dungeontrain$noticeUnsignedBook(Level level, BlockPos pos, Player player,
                                                        ChiseledBookShelfBlockEntity shelf, ItemStack stack,
                                                        int slot, CallbackInfo ci) {
        try {
            UnsignedBookShelfNotice.onBookShelved(player, stack);
        } catch (Throwable t) {
            DUNGEONTRAIN$LOGGER.warn("[DungeonTrain] UnsignedBook: shelf notice failed: {}", t.toString());
        }
    }
}
