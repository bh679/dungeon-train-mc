package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * In-editor variant authoring via shift-right-click:
 *
 * <ul>
 *   <li>Player is sneaking and inside an editor plot.</li>
 *   <li>They look at a block inside the plot footprint.</li>
 *   <li>They right-click it with a placeable block in main hand.</li>
 * </ul>
 *
 * <p>Instead of the vanilla "place the held block on the neighbouring face"
 * behaviour, the held block is <b>appended to the variants list</b> of the
 * targeted block. First edit also captures the block currently occupying the
 * target position as the base candidate, so the entry reads
 * {@code [base_block, added_block]}.</p>
 *
 * <p>The entry is cached in memory (the same cache the runtime
 * {@link CarriageVariantBlocks#loadFor} path reads from). Persist via
 * {@code /dungeontrain editor save}, same as any other editor mutation.</p>
 *
 * <p>Duplicates are allowed — appending the same state twice gives that state
 * 2× weight in the random pick.</p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VariantBlockInteractions {

    /** Soft cap — commands can write more, but the shift-place path stops here to keep feedback readable. */
    private static final int MAX_VARIANTS_PER_POSITION = 16;

    private VariantBlockInteractions() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Server-authoritative: only act on the server side to keep the cache
        // single-source-of-truth and avoid double-processing on integrated SP.
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!player.isShiftKeyDown()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        CarriageVariant plotVariant = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (plotVariant == null) return;

        BlockPos plotOrigin = CarriageEditor.plotOrigin(plotVariant);
        if (plotOrigin == null) return;

        BlockPos clicked = event.getPos();
        BlockPos local = clicked.subtract(plotOrigin);
        if (local.getX() < 0 || local.getX() >= dims.length()
            || local.getY() < 0 || local.getY() >= dims.height()
            || local.getZ() < 0 || local.getZ() >= dims.width()) {
            return;
        }

        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) return;

        BlockState newState = blockItem.getBlock().defaultBlockState();
        if (newState.hasBlockEntity()) {
            player.displayClientMessage(
                Component.literal("Block-entity blocks aren't supported in variants (v1).")
                    .withStyle(ChatFormatting.RED), true);
            suppressVanillaPlace(event);
            return;
        }

        BlockState baseState = level.getBlockState(clicked);
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(plotVariant, dims);
        List<BlockState> existing = sidecar.statesAt(local);

        List<BlockState> updated = new ArrayList<>();
        if (existing == null) {
            // First variant at this position — seed with the base block already
            // placed here so the entry reads [base, newlyAdded] and the random
            // pick chooses between the author's intent and the addition.
            if (baseState.isAir()) {
                player.displayClientMessage(
                    Component.literal("Target block is air — place a base block first.")
                        .withStyle(ChatFormatting.YELLOW), true);
                suppressVanillaPlace(event);
                return;
            }
            if (baseState.hasBlockEntity()) {
                player.displayClientMessage(
                    Component.literal("Base block has a block entity — not supported in variants.")
                        .withStyle(ChatFormatting.RED), true);
                suppressVanillaPlace(event);
                return;
            }
            updated.add(baseState);
            updated.add(newState);
        } else {
            if (existing.size() >= MAX_VARIANTS_PER_POSITION) {
                player.displayClientMessage(
                    Component.literal("Variant list full (" + MAX_VARIANTS_PER_POSITION + ") — clear or reset this position.")
                        .withStyle(ChatFormatting.YELLOW), true);
                suppressVanillaPlace(event);
                return;
            }
            updated.addAll(existing);
            updated.add(newState);
        }

        try {
            sidecar.put(local, updated);
        } catch (IllegalArgumentException e) {
            player.displayClientMessage(
                Component.literal("Variant add failed: " + e.getMessage())
                    .withStyle(ChatFormatting.RED), true);
            suppressVanillaPlace(event);
            return;
        }

        ResourceLocation newName = BuiltInRegistries.BLOCK.getKey(newState.getBlock());
        final int count = updated.size();
        final int lx = local.getX();
        final int ly = local.getY();
        final int lz = local.getZ();
        player.displayClientMessage(
            Component.literal("+ " + newName + "  →  " + count + " variants @ " + lx + "," + ly + "," + lz)
                .withStyle(ChatFormatting.GREEN), true);

        suppressVanillaPlace(event);
    }

    /**
     * Stop vanilla placement/interaction from firing after we've recorded the
     * variant. {@code setUseBlock/setUseItem} to DENY is more surgical than
     * {@code setCanceled}: it leaves the arm-swing animation intact so the
     * client gets "something happened" feedback. We also cancel on the main
     * event so later Forge subscribers don't try to place either.
     */
    private static void suppressVanillaPlace(PlayerInteractEvent.RightClickBlock event) {
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
