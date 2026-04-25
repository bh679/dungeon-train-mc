package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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

import java.io.IOException;
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
        BlockPos clicked = event.getPos();

        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) return;

        BlockState newState = blockItem.getBlock().defaultBlockState();
        if (newState.hasBlockEntity() && !CarriageVariantBlocks.isEmptyPlaceholder(newState)) {
            player.displayClientMessage(
                Component.literal("Block-entity blocks aren't supported in variants (v1).")
                    .withStyle(ChatFormatting.RED), true);
            suppressVanillaPlace(event);
            return;
        }

        // Part plot takes priority: if the clicked position falls inside a
        // part plot, route the shift-click into the part's own variants
        // sidecar. Part rows sit past the carriage row, so a carriage plot
        // and a part plot can't simultaneously contain the same position.
        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(player.blockPosition(), dims);
        if (partLoc != null) {
            handlePartShiftClick(event, player, level, dims, clicked, newState, partLoc);
            return;
        }

        CarriageVariant plotVariant = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (plotVariant == null) return;

        BlockPos plotOrigin = CarriageEditor.plotOrigin(plotVariant);
        if (plotOrigin == null) return;

        BlockPos local = clicked.subtract(plotOrigin);
        if (local.getX() < 0 || local.getX() >= dims.length()
            || local.getY() < 0 || local.getY() >= dims.height()
            || local.getZ() < 0 || local.getZ() >= dims.width()) {
            return;
        }

        BlockState baseState = level.getBlockState(clicked);
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(plotVariant, dims);
        List<BlockState> existing = sidecar.statesAt(local);

        List<BlockState> updated = buildUpdatedList(existing, baseState, newState, player, event);
        if (updated == null) return;

        try {
            sidecar.put(local, updated);
        } catch (IllegalArgumentException e) {
            player.displayClientMessage(
                Component.literal("Variant add failed: " + e.getMessage())
                    .withStyle(ChatFormatting.RED), true);
            suppressVanillaPlace(event);
            return;
        }

        sendAddedFeedback(player, clicked, local, newState, updated);
        VariantOverlayRenderer.pushImmediateHover(player, clicked, updated);
        suppressVanillaPlace(event);
    }

    /**
     * Part-plot branch: resolve {@code (kind, name)} from the player's plot,
     * compute the clicked block's local position inside the part footprint
     * (not the whole carriage), and append the held block to that part's own
     * variants sidecar.
     */
    private static void handlePartShiftClick(PlayerInteractEvent.RightClickBlock event,
                                             ServerPlayer player, ServerLevel level,
                                             CarriageDims dims, BlockPos clicked,
                                             BlockState newState, CarriagePartEditor.PlotLocation loc) {
        CarriagePartKind kind = loc.kind();
        String name = loc.name();
        BlockPos plotOrigin = CarriagePartEditor.plotOrigin(kind, name, dims);
        if (plotOrigin == null) return;

        BlockPos local = clicked.subtract(plotOrigin);
        Vec3i partSize = kind.dims(dims);
        if (local.getX() < 0 || local.getX() >= partSize.getX()
            || local.getY() < 0 || local.getY() >= partSize.getY()
            || local.getZ() < 0 || local.getZ() >= partSize.getZ()) {
            return;
        }

        BlockState baseState = level.getBlockState(clicked);
        CarriagePartVariantBlocks sidecar = CarriagePartVariantBlocks.loadFor(kind, name, partSize);
        List<BlockState> existing = sidecar.statesAt(local);
        List<BlockState> updated = buildUpdatedList(existing, baseState, newState, player, event);
        if (updated == null) return;

        try {
            sidecar.put(local, updated);
            sidecar.save(kind, name);
        } catch (IllegalArgumentException e) {
            player.displayClientMessage(
                Component.literal("Variant add failed: " + e.getMessage())
                    .withStyle(ChatFormatting.RED), true);
            suppressVanillaPlace(event);
            return;
        } catch (IOException e) {
            player.displayClientMessage(
                Component.literal("Variant save failed: " + e.getMessage())
                    .withStyle(ChatFormatting.RED), true);
            suppressVanillaPlace(event);
            return;
        }

        sendAddedFeedback(player, clicked, local, newState, updated);
        VariantOverlayRenderer.pushImmediateHover(player, clicked, updated);
        suppressVanillaPlace(event);
    }

    /**
     * Common "append to variants" logic — seeds with the base block on first
     * edit, enforces the soft cap, and sends user-facing error messages.
     * Returns the new list, or {@code null} if a validation message was sent
     * and the caller should early-return.
     */
    private static List<BlockState> buildUpdatedList(List<BlockState> existing, BlockState baseState,
                                                      BlockState newState, ServerPlayer player,
                                                      PlayerInteractEvent.RightClickBlock event) {
        List<BlockState> updated = new ArrayList<>();
        if (existing == null) {
            if (baseState.isAir()) {
                player.displayClientMessage(
                    Component.literal("Target block is air — place a base block first.")
                        .withStyle(ChatFormatting.YELLOW), true);
                suppressVanillaPlace(event);
                return null;
            }
            if (baseState.hasBlockEntity()) {
                player.displayClientMessage(
                    Component.literal("Base block has a block entity — not supported in variants.")
                        .withStyle(ChatFormatting.RED), true);
                suppressVanillaPlace(event);
                return null;
            }
            updated.add(baseState);
            updated.add(newState);
        } else {
            if (existing.size() >= MAX_VARIANTS_PER_POSITION) {
                player.displayClientMessage(
                    Component.literal("Variant list full (" + MAX_VARIANTS_PER_POSITION + ") — clear or reset this position.")
                        .withStyle(ChatFormatting.YELLOW), true);
                suppressVanillaPlace(event);
                return null;
            }
            updated.addAll(existing);
            updated.add(newState);
        }
        return updated;
    }

    private static void sendAddedFeedback(ServerPlayer player, BlockPos clicked, BlockPos local,
                                           BlockState newState, List<BlockState> updated) {
        ResourceLocation newName = BuiltInRegistries.BLOCK.getKey(newState.getBlock());
        final int count = updated.size();
        final int lx = local.getX();
        final int ly = local.getY();
        final int lz = local.getZ();
        boolean sentinel = CarriageVariantBlocks.isEmptyPlaceholder(newState);
        String label = sentinel ? (newName + " (empty-space)") : newName.toString();
        player.displayClientMessage(
            Component.literal("+ " + label + "  →  " + count + " variants @ " + lx + "," + ly + "," + lz)
                .withStyle(sentinel ? ChatFormatting.AQUA : ChatFormatting.GREEN), true);
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
