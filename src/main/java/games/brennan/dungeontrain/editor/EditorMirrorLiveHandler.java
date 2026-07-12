package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Set;

/**
 * Live editor mirroring: as an author places or breaks a structural block
 * inside an editor plot whose sidecar has any mirror axis enabled, the change
 * is reflected immediately into the mirror-image cells. The save-time pass
 * ({@link EditorMirror#rebuildFromMaster}, called from each editor's
 * {@code save()}) remains the authority and corrects anything live mirroring
 * can't reach (multi-block placements, axis toggled on after earlier edits).
 *
 * <p>Plot resolution reuses the same {@link BlockVariantPlot#resolveAt}
 * cascade as {@link VariantBlockBreakHandler}, so all editor categories
 * (carriage / contents / part / track-side) share this one handler. Writes go
 * through {@link EditorMirror} → {@link games.brennan.dungeontrain.worldgen.SilentBlockOps},
 * which uses raw {@code setBlock} — so this handler never re-triggers its own
 * place / break subscribers.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorMirrorLiveHandler {

    private EditorMirrorLiveHandler() {}

        public static void onBlockPlace(net.minecraft.world.entity.Entity placeEntity, net.minecraft.world.level.LevelAccessor placeLevel, net.minecraft.world.level.block.state.BlockState placedBlock, net.minecraft.core.BlockPos placePos, boolean placeCanceled) {
        if (placeCanceled) return;
        if (!(placeEntity instanceof ServerPlayer player)) return;
        if (!(placeLevel instanceof ServerLevel level)) return;
        applyAt(player, level, placePos, placedBlock);
    }

    @SubscribeEvent
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Each placed cell is already in the world by the time this fires; read
        // back its state and mirror it individually.
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            BlockPos pos = snapshot.getPos();
            applyAt(player, level, pos, level.getBlockState(pos));
        }
    }

        public static void onBlockBreak(net.minecraft.world.level.LevelAccessor breakLevel, net.minecraft.world.entity.player.Player breakPlayer, net.minecraft.core.BlockPos breakPos, net.minecraft.world.level.block.state.BlockState breakState, boolean breakCanceled) {
        if (breakCanceled) return;
        if (!(breakPlayer instanceof ServerPlayer player)) return;
        if (!(breakLevel instanceof ServerLevel level)) return;
        applyAt(player, level, breakPos, null);
    }

    /**
     * Mirror a single edit at world {@code worldPos}: {@code state} is the
     * placed block, or {@code null} for a break (image cells cleared to air).
     * No-op when the player isn't in a mirror-enabled plot or the edit is
     * outside the plot footprint.
     */
    private static void applyAt(ServerPlayer player, ServerLevel level, BlockPos worldPos, BlockState state) {
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) return;
        boolean mx = plot.mirrorX();
        boolean my = plot.mirrorY();
        boolean mz = plot.mirrorZ();
        if (!mx && !my && !mz) return;

        BlockPos local = worldPos.subtract(plot.origin());
        if (!plot.inBounds(local)) return;

        Vec3i footprint = plot.footprint();
        Set<BlockPos> markers = plot.allFlaggedPositions();
        EditorMirror.mirrorEditLive(level, plot.origin(), footprint, local, state, mx, my, mz, markers);
    }
}
