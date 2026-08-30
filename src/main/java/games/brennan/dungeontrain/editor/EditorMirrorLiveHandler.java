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
 * is reflected immediately into the mirror-image cells. This is the mechanism —
 * {@code save()} captures the plot as it stands. Anything live mirroring can't
 * reach (an axis toggled on after earlier edits, clipboard pastes, {@code /fill})
 * is fixed up on demand by {@link EditorMirrorRebuild}, never implicitly at save.
 *
 * <p>Plot resolution runs the same cascade as {@link VariantBlockBreakHandler},
 * so all editor categories (carriage / contents / part / track-side) share this
 * one handler — but keyed on the <i>edited block</i>
 * ({@link BlockVariantPlot#resolveAtPos}) rather than on where the author is
 * standing. A template's mirror settings apply to anything placed inside it,
 * including edits made from the roof, from the gap between plots, or from
 * inside a neighbouring plot.</p>
 *
 * <p>Writes go through {@link EditorMirror} →
 * {@link games.brennan.dungeontrain.worldgen.SilentBlockOps}, which uses raw
 * {@code setBlock} — so this handler never re-triggers its own place / break
 * subscribers.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorMirrorLiveHandler {

    private EditorMirrorLiveHandler() {}

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        applyAt(level, event.getPos(), event.getPlacedBlock());
    }

    @SubscribeEvent
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Each placed cell is already in the world by the time this fires; read
        // back its state and mirror it individually.
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            BlockPos pos = snapshot.getPos();
            applyAt(level, pos, level.getBlockState(pos));
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getPlayer() instanceof ServerPlayer)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        applyAt(level, event.getPos(), null);
    }

    /**
     * Mirror a single edit at world {@code worldPos}: {@code state} is the
     * placed block, or {@code null} for a break (image cells cleared to air).
     * The plot is the one containing {@code worldPos}, so the mirror settings
     * used are the edited template's wherever the author happens to be
     * standing. No-op when the edit isn't inside a mirror-enabled plot.
     *
     * <p>The subscribers keep their "a real player did this" guard, so
     * dispenser / piston writes never reach here.</p>
     */
    private static void applyAt(ServerLevel level, BlockPos worldPos, BlockState state) {
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAtPos(level, worldPos, dims);
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
