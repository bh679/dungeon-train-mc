package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.EditorEditRecorder;
import games.brennan.dungeontrain.editor.EditorVariantMirror;
import games.brennan.dungeontrain.editor.VariantAppend;
import games.brennan.dungeontrain.editor.VariantHotkeyState;
import games.brennan.dungeontrain.editor.VariantOverlayRenderer;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Authors a whole floor / wall / box of block variants in one Effortless Building gesture.
 *
 * <p>Inside an editor plot, with the variant-place key (default {@code Z}) held, an Effortless
 * Building build appends the held block to the variant pool of <em>every</em> cell the shape
 * covers — the multi-cell form of {@link games.brennan.dungeontrain.editor.VariantBlockInteractions},
 * which does one cell per right-click. Nothing is placed: the build is consumed here and
 * Effortless Building's own placement is cancelled by the calling mixin.</p>
 *
 * <p><b>Which cells.</b> Effortless Building's own server pipeline
 * ({@code BuildPipeline.SERVER.runServerPipeline}) is asked for the cell list, so the shape,
 * the mirror / array / radial modifiers and the constraint checks are exactly what it would
 * have placed. Its client offsets the start cell onto the clicked face — a floor drawn on a
 * floor is a layer of air one above it — while variants live on <em>existing</em> blocks, so
 * when the first cell is replaceable the whole set is shifted back through the clicked face
 * ({@link #surfaceShift}). In quick-replace mode the cells already are the existing blocks and
 * nothing moves. Air cells and cells outside the author's plot are skipped and counted.</p>
 *
 * <p><b>Undo.</b> The sidecar is snapshotted once before the first write
 * ({@link EditorEditRecorder#notePendingSidecar}), so one Ctrl+Z reverts the whole build. No
 * block-diff capture is opened — the world does not change.</p>
 *
 * <p><b>Reflection, fails open.</b> Effortless Building is a runtime-optional companion, not a
 * compile dependency, so its packet record and pipeline are reached reflectively, as
 * {@link EffortlessBuildingGate} does. Any failure — a renamed accessor, a changed pipeline
 * signature — returns {@code false} and Effortless Building places normally; the seams are
 * those of {@code effortlessbuilding-4.2+1.21.1}.</p>
 */
public final class EffortlessBuildingVariants {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String EB = "neoforge.nl.requios.effortlessbuilding.";
    private static final String PIPELINE = EB + "buildpipeline.BuildPipeline";
    private static final String BUILD_STATE = EB + "buildpipeline.BuildPipeline$BuildState";
    private static final String BLOCK_SET = EB + "utilities.BlockSet";

    private EffortlessBuildingVariants() {}

    /**
     * Handle one {@code PlaceBuildModePacket} as a bulk variant add.
     *
     * @return true when the build was consumed as variant authoring and the caller must cancel
     *         Effortless Building's placement; false to let it place as usual.
     */
    public static boolean tryVariantBuild(Object packet, ServerPlayer player) {
        if (player == null || !VariantHotkeyState.isHeld(player)) return false;
        if (!(player.level() instanceof ServerLevel level)) return false;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) return false;

        try {
            return authorBuild(packet, player, level, plot);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Effortless Building variant build fell through to normal"
                + " placement: {}", t.toString());
            return false;
        }
    }

    private static boolean authorBuild(Object packet, ServerPlayer player, ServerLevel level,
                                       BlockVariantPlot plot) throws ReflectiveOperationException {
        BlockPos firstPos = (BlockPos) accessor(packet, "firstPos");
        Direction hitFace = (Direction) accessor(packet, "hitFace");
        Vec3 hitLocation = (Vec3) accessor(packet, "hitLocation");

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        BlockHitResult hit = new BlockHitResult(hitLocation, hitFace, firstPos, false);
        VariantState newVariant = VariantAppend.captureHeld(
            level, player, InteractionHand.MAIN_HAND, held, hit, firstPos);
        if (newVariant == null) return false;

        List<BlockPos> cells = runPipeline(packet, player);
        if (cells.isEmpty()) return false;
        boolean shift = level.getBlockState(firstPos).canBeReplaced();
        cells = surfaceShift(cells, shift, hitFace);

        EditorEditRecorder.notePendingSidecar(player, "Variant add");
        int added = 0;
        int skipped = 0;
        String lastReject = null;
        BlockPos lastCell = null;
        List<VariantState> lastPool = null;
        for (BlockPos pos : cells) {
            BlockPos local = pos.subtract(plot.origin());
            if (!plot.inBounds(local)) { skipped++; continue; }
            BlockState baseState = level.getBlockState(pos);
            VariantState base = VariantAppend.captureBase(level, pos, baseState);
            VariantAppend.Result result = VariantAppend.append(plot.statesAt(local), base, newVariant, baseState);
            if (!result.accepted()) { skipped++; lastReject = result.rejectMessage(); continue; }
            try {
                plot.put(local, result.pool());
            } catch (IllegalArgumentException e) {
                skipped++;
                lastReject = e.getMessage();
                continue;
            }
            EditorVariantMirror.mirrorEditLive(level, plot, local, result.pool());
            added++;
            lastCell = pos;
            lastPool = result.pool();
        }

        if (added > 0) {
            try {
                plot.save();
            } catch (IOException e) {
                player.displayClientMessage(
                    Component.literal("Variant save failed: " + e.getMessage())
                        .withStyle(ChatFormatting.RED), true);
                return true;
            }
            VariantOverlayRenderer.pushImmediateHover(player, lastCell, lastPool);
        }
        sendFeedback(player, newVariant, added, skipped, lastReject);
        return true;
    }

    /**
     * Move a build back through the clicked face onto the blocks it was drawn against.
     *
     * <p>Effortless Building's client picks {@code hitPos.relative(hitFace)} as the start cell
     * unless the hit block is itself replaceable (see its {@code resolveFirstClickPos}), and
     * lays the shape out from there. So when {@code firstPosReplaceable} — the start cell is
     * air — every cell is one step off the surface the author was looking at, and the shape is
     * translated by the opposite of the face to land on that surface. Otherwise (quick-replace
     * mode, or building into a replaceable block) the cells already name existing blocks.</p>
     */
    static List<BlockPos> surfaceShift(List<BlockPos> cells, boolean firstPosReplaceable, Direction hitFace) {
        if (!firstPosReplaceable) return cells;
        Direction back = hitFace.getOpposite();
        List<BlockPos> shifted = new ArrayList<>(cells.size());
        for (BlockPos pos : cells) shifted.add(pos.relative(back));
        return shifted;
    }

    /**
     * {@code BuildPipeline.SERVER.runServerPipeline(mode, first, second, third, player, PLACING,
     * fill, cubeFill, raisedEdge, circleStart, protectTileEntities)} → {@code BlockSet.validPositions()}.
     */
    private static List<BlockPos> runPipeline(Object packet, ServerPlayer player) throws ReflectiveOperationException {
        ClassLoader cl = packet.getClass().getClassLoader();
        Class<?> pipelineClass = Class.forName(PIPELINE, true, cl);
        Object pipeline = pipelineClass.getField("SERVER").get(null);
        Class<?> stateClass = Class.forName(BUILD_STATE, true, cl);
        Object placing = null;
        for (Object constant : stateClass.getEnumConstants()) {
            if ("PLACING".equals(String.valueOf(constant))) { placing = constant; break; }
        }
        if (placing == null) throw new NoSuchFieldException("BuildState.PLACING");

        Method run = null;
        for (Method m : pipelineClass.getMethods()) {
            if (m.getName().equals("runServerPipeline") && m.getParameterCount() == 11) { run = m; break; }
        }
        if (run == null) throw new NoSuchMethodException("runServerPipeline/11");
        Object blockSet = run.invoke(pipeline,
            accessor(packet, "buildMode"),
            accessor(packet, "firstPos"),
            accessor(packet, "secondPos"),
            accessor(packet, "thirdPos"),
            (Player) player,
            placing,
            accessor(packet, "fill"),
            accessor(packet, "cubeFill"),
            accessor(packet, "raisedEdge"),
            accessor(packet, "circleStart"),
            accessor(packet, "protectTileEntities"));
        if (blockSet == null) return List.of();

        Object positions = Class.forName(BLOCK_SET, true, cl).getMethod("validPositions").invoke(blockSet);
        List<BlockPos> cells = new ArrayList<>();
        if (positions instanceof List<?> list) {
            for (Object o : list) if (o instanceof BlockPos p) cells.add(p.immutable());
        }
        return cells;
    }

    private static @Nullable Object accessor(Object packet, String name) throws ReflectiveOperationException {
        return packet.getClass().getMethod(name).invoke(packet);
    }

    private static void sendFeedback(ServerPlayer player, VariantState added, int addedCount, int skipped,
                                     @Nullable String lastReject) {
        if (addedCount == 0) {
            String why = lastReject != null ? lastReject : "no cells inside this plot";
            player.displayClientMessage(
                Component.literal("No variants added — " + why).withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        String line = "+ " + VariantAppend.label(added) + "  →  " + addedCount + " cells";
        if (skipped > 0) line += " (" + skipped + " skipped: air / outside plot / full)";
        ChatFormatting colour = added.isMob() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GREEN;
        player.displayClientMessage(Component.literal(line).withStyle(colour), true);
    }
}
