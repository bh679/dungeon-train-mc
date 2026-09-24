package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.MultiBlockVariants;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsPlacement;
import games.brennan.dungeontrain.editor.RotationApplier;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.editor.WholeVariantBlocks;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The per-cell overlay a whole room or group gets after its verbatim stamp: the Z menu's variant
 * pools rolled at {@code (seed, index)}, and the C menu's container prefabs rolled through
 * {@link ContainerContentsPlacement}. The same three behaviours
 * {@link CarriagePlacer#applyVariantBlocks} gives a shell, over a {@link WholeVariantBlocks}
 * sidecar keyed by the plot key {@link BlockVariantPlot#wholeKey}.
 */
public final class WholeOverlay {

    private WholeOverlay() {}

    public static void apply(ServerLevel level, BlockPos origin, WholeKind kind, String id, Vec3i footprint,
                             long seed, int carriageIndex) {
        WholeVariantBlocks sidecar = WholeVariantBlocks.loadFor(kind, id, footprint);
        if (sidecar.isEmpty()) return;
        String plotKey = BlockVariantPlot.wholeKey(kind, id);
        for (CarriageVariantBlocks.Entry e : sidecar.entries()) {
            VariantState picked = sidecar.resolve(e.localPos(), seed, carriageIndex);
            int lockId = sidecar.lockIdAt(e.localPos());
            for (MultiBlockVariants.Write w : MultiBlockVariants.expand(e.states(), picked, e.localPos(),
                    seed, carriageIndex, v -> RotationApplier.apply(
                        StagePlacementScope.resolve(v.state()), v.rotation(), v.half(), v.active(),
                        e.localPos(), seed, carriageIndex, lockId))) {
                BlockPos world = origin.offset(w.localPos());
                if (w.isAir()) {
                    SilentBlockOps.setBlockSilent(level, world, Blocks.AIR.defaultBlockState());
                    continue;
                }
                ContainerContentsPlacement.place(level, world, w.state(), w.entry().blockEntityNbt(),
                    plotKey, w.localPos(), seed, carriageIndex, w.entry().linkedLootPrefabId());
            }
        }
    }
}
