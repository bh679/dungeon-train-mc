package games.brennan.dungeontrain.perks.effects.builder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.train.SharedCarriageLookup;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.common.util.TriState;

/**
 * Where Drift Gift Repaid meets the world: a perk holder opening storage in a drifting carriage gets
 * {@link DriftGiftMenu} instead of the vanilla menu, and a paid gift picked back up off the floor
 * (chest broken, hopper spill) is settled the way a slot pickup is.
 *
 * <p>Listens at {@link EventPriority#LOWEST} so {@code SharedCarriageAdvancementEvents}' open snapshot
 * has already been taken; its close hook then fires for this menu like any other, so the gift still
 * uploads and still counts for the gift advancement.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DriftGiftContainerEvents {

    static {
        BuilderPerks.ensureRegistered();
    }

    private DriftGiftContainerEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isShiftKeyDown() && event.getItemStack().getItem() instanceof BlockItem) return;
        int rank = Perks.rank(player, DriftGiftPayout.ID);
        if (rank <= 0) return;
        BlockPos pos = event.getPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BaseContainerBlockEntity menuBe)) return;
        SharedCarriageRegistry.Instance inst = SharedCarriageLookup.byBlockPos(level, pos);
        if (inst == null || inst.isCulled()) return;
        // Your own build is not somewhere you leave gifts (the generosity and gift hooks agree).
        if (inst.isAuthoredBy(player.getUUID())) return;
        if (!menuBe.canOpen(player)) return; // locked: let vanilla say so
        Container container = containerAt(level, pos, menuBe);
        if (container == null || !DriftGiftMenu.supports(container.getContainerSize())) return;
        Component title = container.getContainerSize() > menuBe.getContainerSize()
                ? Component.translatable("container.chestDouble")
                : ((MenuProvider) menuBe).getDisplayName();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        DriftGiftMenu.open(player, container, title, rank);
    }

    /** Both halves of a double chest, else the block entity itself. */
    private static Container containerAt(ServerLevel level, BlockPos pos, BaseContainerBlockEntity be) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            Container both = ChestBlock.getContainer(chest, state, level, pos, true);
            if (both != null) return both;
        }
        return be;
    }

    /** A paid gift reaching the floor and being picked up: charge the payer, clear the mark for anyone. */
    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItemEntity().getItem();
        if (DriftGiftTag.of(stack) == null) return;
        if (!DriftGiftMenu.settleTake(player, stack)) event.setCanPickup(TriState.FALSE);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DriftGiftLedger.forget(event.getEntity().getUUID());
    }

    /** A new life starts with an empty ledger: the perk's points are per life too. */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!event.isEndConquered()) DriftGiftLedger.forget(event.getEntity().getUUID());
    }
}
