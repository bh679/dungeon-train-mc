package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.EditorEditRecorder;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Pastes a variant clipboard onto a whole floor / wall / box in one Effortless Building gesture.
 *
 * <p>A {@link VariantClipboardItem} pastes one cell per right-click. With Effortless Building's
 * shape modes it becomes a bulk tool: the client arms a build sequence for the clipboard because
 * {@code mixin.effortlessbuilding.EffortlessBuildingTriggerItemMixin} reports it as a build-trigger
 * item, the resulting {@code PlaceBuildModePacket} lands here via
 * {@code EffortlessBuildingPacketHandlerMixin}, and every cell of the shape gets
 * {@link VariantClipboardItem#pasteAt} — the same paste as the single click, so the pool, lock
 * group, copy settings, mirror and container pool all come along.</p>
 *
 * <p><b>Which cells.</b> Effortless Building's own server pipeline
 * ({@code BuildPipeline.SERVER.runServerPipeline}) is asked for the cell list, so the shape, the
 * mirror / array / radial modifiers and the constraint checks are exactly what it would have
 * placed. Its start cell is the clicked face, or the clicked block when that is replaceable — the
 * clipboard's own {@code placePos} rule — so the cells are pasted where they are. Cells outside the
 * author's plot are skipped and counted.</p>
 *
 * <p><b>Undo and saves.</b> One sidecar snapshot before the first write
 * ({@link EditorEditRecorder#notePendingSidecar}) so one Ctrl+Z reverts the whole paste; the plot
 * and its contents store are saved once at the end rather than per cell. No item is consumed —
 * this is editor authoring.</p>
 *
 * <p><b>Reflection, fails open.</b> Effortless Building is a runtime-optional companion, not a
 * compile dependency, so its packet record and pipeline are reached reflectively, as
 * {@link EffortlessBuildingGate} does. Any failure returns {@code false} and Effortless Building's
 * handler runs as normal (which, for a clipboard, places nothing); the seams are those of
 * {@code effortlessbuilding-4.2+1.21.1}.</p>
 */
public final class EffortlessBuildingVariants {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String EB = "neoforge.nl.requios.effortlessbuilding.";
    private static final String PIPELINE = EB + "buildpipeline.BuildPipeline";
    private static final String BUILD_STATE = EB + "buildpipeline.BuildPipeline$BuildState";
    private static final String BLOCK_SET = EB + "utilities.BlockSet";

    private EffortlessBuildingVariants() {}

    /**
     * Handle one {@code PlaceBuildModePacket} as a bulk clipboard paste.
     *
     * @return true when the build was consumed and the caller must cancel Effortless Building's
     *         own handler; false to let it run as usual.
     */
    public static boolean tryClipboardBuild(Object packet, ServerPlayer player) {
        if (player == null) return false;
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(held.getItem() instanceof VariantClipboardItem)) return false;
        if (!(player.level() instanceof ServerLevel level)) return false;
        if (!player.hasPermissions(2)) {
            actionBar(player, "Variant clipboard requires OP", ChatFormatting.RED);
            return true;
        }

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            actionBar(player, "Stand inside a block-variant editor plot to paste", ChatFormatting.YELLOW);
            return true;
        }

        try {
            return pasteBuild(packet, player, level, plot, held);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Effortless Building clipboard paste fell through: {}", t.toString());
            return false;
        }
    }

    private static boolean pasteBuild(Object packet, ServerPlayer player, ServerLevel level,
                                      BlockVariantPlot plot, ItemStack held) throws ReflectiveOperationException {
        List<BlockPos> cells = runPipeline(packet, player);
        if (cells.isEmpty()) return false;

        EditorEditRecorder.notePendingSidecar(player, "Clipboard paste");
        int pasted = 0;
        int skipped = 0;
        VariantClipboardItem.PasteOutcome last = null;
        String firstError = null;
        for (BlockPos pos : cells) {
            VariantClipboardItem.PasteOutcome outcome = VariantClipboardItem.pasteAt(level, player, plot, pos, held);
            if (outcome.error() != null) {
                skipped++;
                if (firstError == null) firstError = outcome.error();
                continue;
            }
            pasted++;
            last = outcome;
        }

        if (pasted == 0) {
            actionBar(player, "Nothing pasted — " + (firstError != null ? firstError : "no cells"), ChatFormatting.YELLOW);
            return true;
        }
        try {
            plot.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] VariantClipboard bulk save failed for {}: {}", plot.key(), e.toString());
            actionBar(player, "Save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
            return true;
        }
        boolean poolPasted = VariantClipboardItem.savePool(player, plot, last);

        String line = "Pasted " + last.stateCount() + " variants onto " + pasted + " cells";
        if (last.lockId() > 0) line += " (lock-id " + last.lockId() + ")";
        if (poolPasted) line += " +pool(" + last.pool().size() + ")";
        if (skipped > 0) line += " — " + skipped + " outside plot";
        actionBar(player, line, ChatFormatting.GREEN);
        return true;
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

    private static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }
}
