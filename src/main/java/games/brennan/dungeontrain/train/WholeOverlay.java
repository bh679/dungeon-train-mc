package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.GrowthPass;
import games.brennan.dungeontrain.editor.VariantConditions;
import games.brennan.dungeontrain.editor.ConnectPass;
import games.brennan.dungeontrain.editor.MultiBlockVariants;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsPlacement;
import games.brennan.dungeontrain.editor.RotationApplier;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.editor.WholeVariantBlocks;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
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

    private static final Logger LOGGER = LogUtils.getLogger();

    private WholeOverlay() {}

    public static void apply(ServerLevel level, BlockPos origin, WholeKind kind, String id, Vec3i footprint,
                             long seed, int carriageIndex) {
        WholeVariantBlocks sidecar = WholeVariantBlocks.loadFor(kind, id, footprint);
        if (sidecar.isEmpty()) return;
        String plotKey = BlockVariantPlot.wholeKey(kind, id);
        java.util.function.Predicate<BlockPos> within =
            GrowthPass.inside(origin, footprint.getX(), footprint.getY(), footprint.getZ());
        try (ConnectPass.Scope ignored = ConnectPass.open();
             GrowthPass.Scope grown = GrowthPass.open();
             VariantConditions.Scope conds = VariantConditions.open(
                 VariantConditions.stamped(level, origin::offset, footprint))) {
            for (CarriageVariantBlocks.Entry e : sidecar.entries()) {
                VariantState picked = sidecar.resolve(e.localPos(), seed, carriageIndex);
                int lockId = sidecar.lockIdAt(e.localPos());
                for (MultiBlockVariants.Write w : MultiBlockVariants.expand(e.states(), sidecar.spanAt(e.localPos()), picked, e.localPos(),
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
                    ConnectPass.note(level, world, w.entry().connect(), w.state());
                    GrowthPass.note(level, world, w.entry(), w.state(), w.localPos(), seed, carriageIndex, within);
                }
            }
            // The mob cells, as the shell pass spawns its own: a mob pick carries the empty placeholder
            // state, so the block loop above has already aired the cell and this only spawns the entity.
            // The contents placer decides between "as authored" (a Test the Carriage stamp) and the
            // onboarding ramp from the carriage index.
            int spawned = 0;
            for (CarriageVariantBlocks.Entry e : sidecar.entries()) {
                VariantState picked = sidecar.resolve(e.localPos(), seed, carriageIndex);
                if (picked == null || !picked.isMob()) continue;
                if (CarriageContentsPlacer.spawnVariantMob(level, origin.offset(e.localPos()), picked, carriageIndex)) {
                    spawned++;
                }
            }
            if (spawned > 0) {
                LOGGER.info("[DungeonTrain] Mob-variant: spawned {} whole mobs for {} '{}' pIdx={}",
                    spawned, kind.id(), id, carriageIndex);
            }
        }
    }
}
