package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * In-editor variant authoring via the rebindable variant-place key
 * (default {@code Z}) plus right-click:
 *
 * <ul>
 *   <li>Player is holding the variant-place key and inside an editor plot.</li>
 *   <li>They look at a block inside the plot footprint.</li>
 *   <li>They right-click it with a placeable block, a spawn egg, or a filled
 *       bucket in main hand. A bucket contributes the fluid's <b>source</b>
 *       state — see {@link VariantLiquids}.</li>
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
 * <p>Capture and the append rule (base-block seed, cap, orientation) live in
 * {@link VariantAppend}, shared with the Effortless Building bulk path
 * ({@code compat.EffortlessBuildingVariants}) so a whole floor of variants
 * is authored by exactly the rules a single right-click follows.</p>
 *
 * <p>The entry is cached in memory (the same cache the runtime
 * {@link CarriageVariantBlocks#loadFor} path reads from). Persist via
 * {@code /dungeontrain editor save}, same as any other editor mutation.</p>
 *
 * <p>Duplicates are allowed — appending the same state twice gives that state
 * 2× weight in the random pick.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class VariantBlockInteractions {

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
        if (held.isEmpty()) return;

        VariantState newVariant = VariantAppend.captureHeld(
            level, player, event.getHand(), held, event.getHitVec(), clicked);
        // Empty / milk bucket and every other non-variant item: fall through to
        // vanilla use rather than swallowing the interaction.
        if (newVariant == null) return;

        // Snapshot the sidecar before any of the four per-kind branches touches
        // it, so this add joins the tick's undo step. One call covers all four:
        // they differ in which sidecar they write, not in whether they write.
        EditorEditRecorder.notePendingSidecar(player, "Variant add");

        // A Train Builder world has no plot grid, so not one of the four editor branches below can
        // resolve anything in it — the gesture would arm and then quietly place the block. Its plot
        // answers from world data instead, which is why it is asked first and by level rather than
        // by where the player is standing.
        BlockVariantPlot builderPlot = games.brennan.dungeontrain.builder.BuilderCarriagePlot.of(
            level, player.blockPosition(), dims);
        if (builderPlot != null) {
            handleBuilderShiftClick(event, player, level, clicked, newVariant, builderPlot);
            return;
        }

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
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(
            plotVariant, CarriageEditor.plotDims(plotVariant, dims));
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

        mirrorVariantAdd(level, player, clicked, updated);
        sendAddedFeedback(player, clicked, local, newVariant, updated);
        VariantOverlayRenderer.pushImmediateHover(player, clicked, updated);
        suppressVanillaPlace(event);
    }

    /**
     * Train Builder branch: append the held block to the build's own working sidecar.
     *
     * <p>Written through immediately, unlike the carriage branch above, which leaves the sidecar
     * dirty for {@code /dt editor save}. There is no such command down here — the builder's Save
     * writes a <em>template</em>, and an author who flags a few blocks and then quits without ever
     * naming the build should still find them there next time.</p>
     */
    private static void handleBuilderShiftClick(PlayerInteractEvent.RightClickBlock event,
                                                ServerPlayer player, ServerLevel level,
                                                BlockPos clicked, VariantState newVariant,
                                                BlockVariantPlot plot) {
        BlockPos local = clicked.subtract(plot.origin());
        if (!plot.inBounds(local)) return;

        BlockState baseState = level.getBlockState(clicked);
        VariantState baseVariant = captureBaseVariant(level, clicked, baseState);
        List<VariantState> updated = buildUpdatedList(plot.statesAt(local), baseVariant, newVariant,
            baseState, player, event);
        if (updated == null) return;

        try {
            plot.put(local, updated);
            plot.save();
        } catch (IllegalArgumentException | IOException e) {
            player.displayClientMessage(
                Component.literal("Variant add failed: " + e.getMessage())
                    .withStyle(ChatFormatting.RED), true);
            suppressVanillaPlace(event);
            return;
        }

        mirrorVariantAdd(level, player, clicked, updated);
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
        BlockPos plotOrigin = CarriagePartEditor.plotOrigin(
            new games.brennan.dungeontrain.template.CarriagePartTemplateId(kind, name), dims);
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

        mirrorVariantAdd(level, player, clicked, updated);
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
        Vec3i interiorSize = CarriageContentsPlacer.interiorSizeFor(contents, dims);

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

        mirrorVariantAdd(level, player, clicked, updated);
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

        mirrorVariantAdd(level, player, clicked, updated);
        sendAddedFeedback(player, clicked, local, newVariant, updated);
        VariantOverlayRenderer.pushImmediateHover(player, clicked, updated);
        suppressVanillaPlace(event);
    }

    /** See {@link VariantAppend#captureBase}. */
    private static @Nullable VariantState captureBaseVariant(ServerLevel level, BlockPos clicked, BlockState rawBaseState) {
        return VariantAppend.captureBase(level, clicked, rawBaseState);
    }

    /**
     * {@link VariantAppend#append} plus this path's rejection handling: the
     * message goes to the action bar and vanilla placement is suppressed.
     * Returns the new list, or {@code null} if the caller should early-return.
     */
    private static @Nullable List<VariantState> buildUpdatedList(List<VariantState> existing,
                                                                @Nullable VariantState baseVariant,
                                                                VariantState newVariant, BlockState baseState,
                                                                ServerPlayer player,
                                                                PlayerInteractEvent.RightClickBlock event) {
        VariantAppend.Result result = VariantAppend.append(existing, baseVariant, newVariant, baseState);
        if (!result.accepted()) {
            player.displayClientMessage(
                Component.literal(result.rejectMessage()).withStyle(ChatFormatting.YELLOW), true);
            suppressVanillaPlace(event);
            return null;
        }
        return result.pool();
    }

    private static void sendAddedFeedback(ServerPlayer player, BlockPos clicked, BlockPos local,
                                           VariantState added, List<VariantState> updated) {
        final int count = updated.size();
        final int lx = local.getX();
        final int ly = local.getY();
        final int lz = local.getZ();
        ChatFormatting colour = added.isMob() ? ChatFormatting.LIGHT_PURPLE
            : CarriageVariantBlocks.isEmptyPlaceholder(added.state()) ? ChatFormatting.AQUA
            : ChatFormatting.GREEN;
        player.displayClientMessage(
            Component.literal("+ " + VariantAppend.label(added) + "  →  " + count + " variants @ " + lx + "," + ly + "," + lz)
                .withStyle(colour), true);
    }

    /**
     * Mirror a just-recorded variant edit to the symmetric cells when the plot's
     * "V" toggle is on. Re-resolves the {@link BlockVariantPlot} and recomputes
     * {@code local} from its origin — the exact frame {@link VariantBlockBreakHandler}
     * uses — so the image cells line up regardless of which branch recorded the edit.
     */
    private static void mirrorVariantAdd(ServerLevel level, ServerPlayer player, BlockPos clicked,
                                         List<VariantState> updated) {
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) return;
        BlockPos local = clicked.subtract(plot.origin());
        if (!plot.inBounds(local)) return;
        EditorVariantMirror.mirrorEditLive(level, plot, local, updated);
    }

    /**
     * Stop vanilla placement/interaction from firing after we've recorded the
     * variant. {@code setUseBlock/setUseItem} to DENY is more surgical than
     * {@code setCanceled}: it leaves the arm-swing animation intact so the
     * client gets "something happened" feedback. We also cancel on the main
     * event so later Forge subscribers don't try to place either.
     */
    private static void suppressVanillaPlace(PlayerInteractEvent.RightClickBlock event) {
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
