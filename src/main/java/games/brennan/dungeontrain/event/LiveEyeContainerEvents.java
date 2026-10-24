package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.SharedCarriageLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The moments a player sees a container's Live Eyes: opening it, and breaking it (a vase shows its
 * item only when it shatters). Each resolves the container's pending Live Eyes for that player
 * before the menu opens or the contents spill ({@link LiveEyeLootResolver}). Jade's look-at tooltip
 * is the third moment ({@code compat/jade/LiveEyeJadePlugin}); a stack that slips past all three
 * resolves when it reaches an inventory ({@link LiveHeadpieceLootGate}).
 *
 * <p>Drifting shared carriages are skipped: their containers sync to the relay on close, so a
 * resolution there would reach other worlds. Their pending stacks resolve on pickup instead.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveEyeContainerEvents {

    private LiveEyeContainerEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Sneaking with a block in hand places against the container instead of opening it.
        if (player.isShiftKeyDown() && event.getItemStack().getItem() instanceof BlockItem) return;
        BlockPos pos = event.getPos();
        // Menu containers only: right-clicking a vase shows nothing, so it is not "seeing" it.
        if (!(level.getBlockEntity(pos) instanceof BaseContainerBlockEntity)) return;
        see(player, level, pos);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        see(player, level, event.getPos());
    }

    /** Resolve the container at {@code pos} for {@code player}. Shared by the Jade plugin. */
    public static void see(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Container container = containerAt(level, pos);
        if (container == null || !LiveEyeLootResolver.holdsAnyLiveEye(container)) return;
        if (SharedCarriageLookup.byBlockPos(level, pos) != null) return;
        if (LiveEyeLootResolver.resolveContainer(player, container)) container.setChanged();
    }

    /** The container a player sees at {@code pos}: both halves of a double chest, else the block entity. */
    @Nullable
    private static Container containerAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            Container both = ChestBlock.getContainer(chest, state, level, pos, true);
            if (both != null) return both;
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }
}
