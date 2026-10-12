package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.PhotographFrameBlock;
import games.brennan.dungeontrain.block.entity.PhotographFrameBlockEntity;
import games.brennan.dungeontrain.registry.ModBlocks;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.world.item.GlassPhotographFrameItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Hangs Exposure's photo frames as {@link PhotographFrameBlock} instead of the entity, on walls.
 *
 * <p>{@link #tryPlace} is called from {@code PhotographFrameItemPlaceMixin} at the head of
 * {@code PhotographFrameItem.useOn} — after vanilla has given the clicked block its own use, so a
 * frame in hand still opens a chest. It mirrors Exposure's item: the largest size (3×3 → 1×1) that
 * fits wins, growing up and counter-clockwise from the clicked face. Floors, ceilings and walls with
 * no room at all fall through to Exposure, which hangs its entity as before.</p>
 *
 * <p>Runs on both sides with the same checks, so the client predicts the arm swing and the server
 * places; only the server writes.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PhotoFrameBlockPlacer {

    /** Neighbour shapes are not updated while the cells go down; the group is half-built until the last one. */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private PhotoFrameBlockPlacer() {}

    /** The result to hand back from {@code useOn}, or {@code null} to let Exposure place its entity. */
    @Nullable
    public static InteractionResult tryPlace(UseOnContext context) {
        Direction facing = context.getClickedFace();
        if (!facing.getAxis().isHorizontal()) return null;
        Player player = context.getPlayer();
        if (player == null) return null;
        Level level = context.getLevel();
        ItemStack stack = context.getItemInHand();
        BlockPos anchor = context.getClickedPos().relative(facing);

        for (int size = PhotoFrameLayout.MAX_SIZE; size >= 0; size--) {
            BlockPos master = PhotoFrameLayout.masterForAnchor(anchor, size);
            List<PhotoFrameLayout.Cell> cells = PhotoFrameLayout.cells(master, facing, size);
            if (!fits(level, player, stack, facing, cells)) continue;
            if (!level.isClientSide) place(level, player, stack, facing, size, cells);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return null;
    }

    private static boolean fits(Level level, Player player, ItemStack stack, Direction facing,
                                List<PhotoFrameLayout.Cell> cells) {
        for (PhotoFrameLayout.Cell cell : cells) {
            BlockPos pos = cell.pos();
            if (!level.isInWorldBounds(pos)) return false;
            if (!level.getBlockState(pos).canBeReplaced()) return false;
            if (!level.getBlockState(PhotoFrameLayout.wallBehind(pos, facing)).isSolid()) return false;
            if (!player.mayUseItemAt(pos, facing, stack)) return false;
        }
        return true;
    }

    private static void place(Level level, Player player, ItemStack stack, Direction facing, int size,
                              List<PhotoFrameLayout.Cell> cells) {
        BlockState base = ModBlocks.PHOTOGRAPH_FRAME.get().defaultBlockState()
            .setValue(PhotographFrameBlock.FACING, facing)
            .setValue(PhotographFrameBlock.GLASS, stack.getItem() instanceof GlassPhotographFrameItem);
        for (PhotoFrameLayout.Cell cell : cells) {
            level.setBlock(cell.pos(), base
                .setValue(PhotographFrameBlock.X_OFFSET, cell.xOffset())
                .setValue(PhotographFrameBlock.Y_OFFSET, cell.yOffset()), PLACE_FLAGS);
        }
        BlockPos master = cells.get(0).pos();
        if (level.getBlockEntity(master) instanceof PhotographFrameBlockEntity be) {
            be.init(stack, size);
        }
        for (PhotoFrameLayout.Cell cell : cells) {
            level.blockUpdated(cell.pos(), ModBlocks.PHOTOGRAPH_FRAME.get());
        }
        stack.consume(1, player);
        level.playSound(null, master, Exposure.SoundEvents.PHOTOGRAPH_FRAME_PLACE.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
        level.gameEvent(player, GameEvent.BLOCK_PLACE, master);
    }

    /**
     * Left-clicking a frame that holds a photo takes the photo out instead of breaking the frame, as
     * it does on Exposure's entity. Cancelled on the client too, so it never starts a break.
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        Level level = event.getLevel();
        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof PhotographFrameBlock)) return;
        if (PhotographFrameBlock.popPhoto(level, event.getPos(), state, event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
