package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsTemplate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * In-editor variant authoring via the rebindable variant-place key
 * (default {@code Z}) plus right-click:
 *
 * <ul>
 *   <li>Player is holding the variant-place key and inside an editor plot.</li>
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
 * <p>Held-state for the variant key lives in {@link VariantHotkeyState}, which
 * is updated by the client-side {@code VariantHotkeyClient} via the
 * {@link games.brennan.dungeontrain.net.VariantHotkeyPacket}. Vanilla sneak
 * no longer triggers variant placement.</p>
 *
 * <p>v2 capture: the held item's BlockState is derived from a
 * {@link BlockPlaceContext} so directional blocks (stairs, logs, doors,
 * repeaters, …) get the player's intended facing — not the default. When the
 * resulting block has a {@link BlockEntity}, the BE NBT is captured from the
 * held item's {@code BlockEntityTag} (vanilla Ctrl+Pick captures this) with a
 * fallback to the clicked block's existing BE.</p>
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
        if (!VariantHotkeyState.isHeld(player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockPos clicked = event.getPos();

        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) return;

        VariantState newVariant = captureVariant(event, level, player, blockItem, held, clicked);
        if (newVariant == null) return;

        // Part plot takes priority: if the clicked position falls inside a
        // part plot, route the shift-click into the part's own variants
        // sidecar. Part rows sit past the carriage row, so a carriage plot
        // and a part plot can't simultaneously contain the same position.
        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(player.blockPosition(), dims);
        if (partLoc != null) {
            handlePartShiftClick(event, player, level, dims, clicked, newVariant, partLoc);
            return;
        }

        // Contents plot dispatch — sits between part and carriage. Contents
        // and carriage rows occupy distinct Z bands so they can't overlap.
        CarriageContents contentsPlot = CarriageContentsEditor.plotContaining(player.blockPosition(), dims);
        if (contentsPlot != null) {
            handleContentsShiftClick(event, player, level, dims, clicked, newVariant, contentsPlot);
            return;
        }

        // Track-side plots (track tile / pillar section / stairs adjunct
        // / tunnel kind) live in their own X/Z rows past the carriage row,
        // so they also can't overlap a carriage plot. Route shift-clicks
        // into the per-kind {@link TrackVariantBlocks} sidecar.
        TrackPlotLocator.PlotInfo trackLoc = TrackPlotLocator.locate(player, dims);
        if (trackLoc != null) {
            handleTrackShiftClick(event, player, level, clicked, newVariant, trackLoc);
            return;
        }

        CarriageVariant plotVariant = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (plotVariant == null) return;

        BlockPos plotOrigin = CarriageEditor.plotOrigin(plotVariant, dims);
        if (plotOrigin == null) return;

        BlockPos local = clicked.subtract(plotOrigin);
        if (local.getX() < 0 || local.getX() >= dims.length()
            || local.getY() < 0 || local.getY() >= dims.height()
            || local.getZ() < 0 || local.getZ() >= dims.width()) {
            return;
        }

        BlockState baseState = level.getBlockState(clicked);
        VariantState baseVariant = captureBaseVariant(level, clicked, baseState);
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(plotVariant, dims);
        List<VariantState> existing = sidecar.statesAt(local);

        List<VariantState> updated = buildUpdatedList(existing, baseVariant, newVariant, baseState, player, event);
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

        sendAddedFeedback(player, clicked, local, newVariant, updated);
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
                                             VariantState newVariant, CarriagePartEditor.PlotLocation loc) {
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
        VariantState baseVariant = captureBaseVariant(level, clicked, baseState);
        CarriagePartVariantBlocks sidecar = CarriagePartVariantBlocks.loadFor(kind, name, partSize);
        List<VariantState> existing = sidecar.statesAt(local);
        List<VariantState> updated = buildUpdatedList(existing, baseVariant, newVariant, baseState, player, event);
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

        sendAddedFeedback(player, clicked, local, newVariant, updated);
        VariantOverlayRenderer.pushImmediateHover(player, clicked, updated);
        suppressVanillaPlace(event);
    }

    /**
     * Contents-plot branch: resolve the contents id from the player's plot,
     * compute the clicked block's local position inside the interior volume
     * (carriageOrigin + (1,1,1)), and append the held block to that contents'
     * own variants sidecar. Eager-saves the sidecar — same as the part path —
     * so author can shift-click and walk away without an explicit save.
     */
    private static void handleContentsShiftClick(PlayerInteractEvent.RightClickBlock event,
                                                 ServerPlayer player, ServerLevel level,
                                                 CarriageDims dims, BlockPos clicked,
                                                 VariantState newVariant, CarriageContents contents) {
        BlockPos carriageOrigin = CarriageContentsEditor.plotOrigin(contents, dims);
        if (carriageOrigin == null) return;
        BlockPos interiorOrigin = carriageOrigin.offset(1, 1, 1);
        Vec3i interiorSize = CarriageContentsTemplate.interiorSize(dims);

        BlockPos local = clicked.subtract(interiorOrigin);
        if (local.getX() < 0 || local.getX() >= interiorSize.getX()
            || local.getY() < 0 || local.getY() >= interiorSize.getY()
            || local.getZ() < 0 || local.getZ() >= interiorSize.getZ()) {
            // Clicked the shell or outside the cage — silently skip so the
            // shell stamps don't get treated as variant base blocks.
            return;
        }

        BlockState baseState = level.getBlockState(clicked);
        VariantState baseVariant = captureBaseVariant(level, clicked, baseState);
        CarriageContentsVariantBlocks sidecar = CarriageContentsVariantBlocks.loadFor(contents, interiorSize);
        List<VariantState> existing = sidecar.statesAt(local);
        List<VariantState> updated = buildUpdatedList(existing, baseVariant, newVariant, baseState, player, event);
        if (updated == null) return;

        try {
            sidecar.put(local, updated);
            sidecar.save(contents);
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

        sendAddedFeedback(player, clicked, local, newVariant, updated);
        VariantOverlayRenderer.pushImmediateHover(player, clicked, updated);
        suppressVanillaPlace(event);
    }

    /**
     * Track-side branch: append the held variant to the
     * {@link games.brennan.dungeontrain.track.variant.TrackVariantBlocks}
     * sidecar for the kind/name resolved from {@code loc}. Mirrors
     * {@link #handlePartShiftClick} — same author flow, different sidecar
     * (per-{@code (TrackKind, name)} JSON next to the kind's NBT).
     */
    private static void handleTrackShiftClick(PlayerInteractEvent.RightClickBlock event,
                                              ServerPlayer player, ServerLevel level,
                                              BlockPos clicked, VariantState newVariant,
                                              TrackPlotLocator.PlotInfo loc) {
        BlockPos plotOrigin = loc.origin();
        Vec3i footprint = loc.footprint();
        BlockPos local = clicked.subtract(plotOrigin);
        if (local.getX() < 0 || local.getX() >= footprint.getX()
            || local.getY() < 0 || local.getY() >= footprint.getY()
            || local.getZ() < 0 || local.getZ() >= footprint.getZ()) {
            return;
        }

        BlockState baseState = level.getBlockState(clicked);
        VariantState baseVariant = captureBaseVariant(level, clicked, baseState);
        games.brennan.dungeontrain.track.variant.TrackVariantBlocks sidecar =
            games.brennan.dungeontrain.track.variant.TrackVariantBlocks.loadFor(
                loc.kind(), loc.name(), footprint);
        List<VariantState> existing = sidecar.statesAt(local);
        List<VariantState> updated = buildUpdatedList(existing, baseVariant, newVariant, baseState, player, event);
        if (updated == null) return;

        try {
            sidecar.put(local, updated);
            sidecar.save(loc.kind(), loc.name());
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

        sendAddedFeedback(player, clicked, local, newVariant, updated);
        VariantOverlayRenderer.pushImmediateHover(player, clicked, updated);
        suppressVanillaPlace(event);
    }

    /**
     * Derive a {@link VariantState} from the held {@link BlockItem} at the
     * shift-click target. The {@link BlockState} is computed via
     * {@link BlockPlaceContext} so directional properties (FACING / AXIS /
     * HALF / SHAPE) match what vanilla placement would have produced for the
     * player's look direction. Block-entity NBT is captured from the held
     * item's {@code BlockEntityTag} first (vanilla Ctrl+Pick stamps this on
     * the picked stack), with a fallback to the clicked block's existing BE
     * — useful for the "I edited a chest in-world, now copy it to a variant"
     * authoring loop.
     */
    private static @Nullable VariantState captureVariant(PlayerInteractEvent.RightClickBlock event,
                                                          ServerLevel level, ServerPlayer player,
                                                          BlockItem blockItem, ItemStack held, BlockPos clicked) {
        BlockPlaceContext ctx = new BlockPlaceContext(
            new UseOnContext(level, player, event.getHand(), held, event.getHitVec()));
        BlockState newState = blockItem.getBlock().getStateForPlacement(ctx);
        if (newState == null) newState = blockItem.getBlock().defaultBlockState();

        CompoundTag beNbt = null;
        if (newState.hasBlockEntity()) {
            beNbt = BlockItem.getBlockEntityData(held);
            if (beNbt == null) {
                BlockState clickedState = level.getBlockState(clicked);
                if (clickedState.is(newState.getBlock())) {
                    BlockEntity be = level.getBlockEntity(clicked);
                    if (be != null) beNbt = be.saveWithoutMetadata();
                }
            }
        }
        return new VariantState(newState, beNbt);
    }

    /**
     * Capture the cell's existing block as a {@link VariantState} so the
     * first shift-click seeds the candidate list with {@code [base, added]}.
     * Block-entity payloads are read from the world so an authored chest /
     * sign / banner round-trips into the variant list with its contents.
     * Returns {@code null} for air — the caller surfaces the "place a base
     * block first" toast separately.
     */
    private static @Nullable VariantState captureBaseVariant(ServerLevel level, BlockPos clicked, BlockState baseState) {
        if (baseState.isAir()) return null;
        CompoundTag beNbt = null;
        if (baseState.hasBlockEntity()) {
            BlockEntity be = level.getBlockEntity(clicked);
            if (be != null) beNbt = be.saveWithoutMetadata();
        }
        return new VariantState(baseState, beNbt);
    }

    /**
     * Common "append to variants" logic — seeds with the base block on first
     * edit, enforces the soft cap, and sends user-facing error messages.
     * Returns the new list, or {@code null} if a validation message was sent
     * and the caller should early-return.
     */
    private static List<VariantState> buildUpdatedList(List<VariantState> existing, @Nullable VariantState baseVariant,
                                                      VariantState newVariant, BlockState baseState,
                                                      ServerPlayer player,
                                                      PlayerInteractEvent.RightClickBlock event) {
        List<VariantState> updated = new ArrayList<>();
        if (existing == null) {
            if (baseVariant == null || baseState.isAir()) {
                player.displayClientMessage(
                    Component.literal("Target block is air — place a base block first.")
                        .withStyle(ChatFormatting.YELLOW), true);
                suppressVanillaPlace(event);
                return null;
            }
            updated.add(baseVariant);
            updated.add(newVariant);
        } else {
            if (existing.size() >= MAX_VARIANTS_PER_POSITION) {
                player.displayClientMessage(
                    Component.literal("Variant list full (" + MAX_VARIANTS_PER_POSITION + ") — clear or reset this position.")
                        .withStyle(ChatFormatting.YELLOW), true);
                suppressVanillaPlace(event);
                return null;
            }
            updated.addAll(existing);
            updated.add(newVariant);
        }
        return updated;
    }

    private static void sendAddedFeedback(ServerPlayer player, BlockPos clicked, BlockPos local,
                                           VariantState added, List<VariantState> updated) {
        ResourceLocation newName = BuiltInRegistries.BLOCK.getKey(added.state().getBlock());
        final int count = updated.size();
        final int lx = local.getX();
        final int ly = local.getY();
        final int lz = local.getZ();
        boolean sentinel = CarriageVariantBlocks.isEmptyPlaceholder(added.state());
        StringBuilder label = new StringBuilder();
        if (sentinel) {
            label.append(newName).append(" (empty-space)");
        } else {
            label.append(newName);
            if (added.hasBlockEntityData()) label.append(" (+nbt)");
        }
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
