package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.LostCitySeating;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Draws the terrain up to the Lost City's buildings without carving it above them.
 *
 * <p>Vanilla's beard does both at once, so those structures answer {@code NONE} and are left out of the
 * beardifier's own piece list ({@code StructureTerrainAdaptationMixin}). This mixin collects their rigid
 * pieces near the chunk into its own list when the beardifier is built, and adds
 * {@link LostCitySeating#fillContribution} for each at the end of every density sample: the fill half of
 * vanilla's formula, a Gaussian skirt within 12 blocks of the box and a fill under the pad, never a cut.</p>
 */
@Mixin(Beardifier.class)
public abstract class BeardifierMixin {

    /** Rigid boxes of the Lost City pieces near this chunk, with their ground level; empty away from a city. */
    @Unique
    private List<Beardifier.Rigid> dungeontrain$lostCity = List.of();

    @Inject(method = "forStructuresInChunk", at = @At("RETURN"))
    private static void dungeontrain$collectLostCity(StructureManager structureManager, ChunkPos chunkPos,
                                                     CallbackInfoReturnable<Beardifier> cir) {
        List<Beardifier.Rigid> rigid = null;
        for (StructureStart start : structureManager.startsForStructure(chunkPos, LostCitySeating::isLostCity)) {
            for (StructurePiece piece : start.getPieces()) {
                if (!piece.isCloseToChunk(chunkPos, LostCitySeating.KERNEL_RADIUS)) continue;
                if (!(piece instanceof PoolElementStructurePiece pool)) continue;
                if (pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) continue;
                if (rigid == null) rigid = new ObjectArrayList<>();
                rigid.add(new Beardifier.Rigid(pool.getBoundingBox(), TerrainAdjustment.BEARD_THIN, pool.getGroundLevelDelta()));
            }
        }
        if (rigid != null) ((BeardifierMixin) (Object) cir.getReturnValue()).dungeontrain$lostCity = rigid;
    }

    @Inject(method = "compute", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$fillLostCity(DensityFunction.FunctionContext ctx, CallbackInfoReturnable<Double> cir) {
        List<Beardifier.Rigid> rigid = dungeontrain$lostCity;
        if (rigid.isEmpty()) return;
        int x = ctx.blockX();
        int y = ctx.blockY();
        int z = ctx.blockZ();
        double add = 0.0;
        for (Beardifier.Rigid r : rigid) {
            BoundingBox box = r.box();
            int dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
            int dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
            int dy = y - (box.minY() + r.groundLevelDelta());
            add += LostCitySeating.fillContribution(dx, dy, dz);
        }
        if (add != 0.0) cir.setReturnValue(cir.getReturnValue() + add);
    }
}
