package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.mixin.SinglePoolElementAccessor;
import games.brennan.dungeontrain.mixin.StructurePieceAccessor;
import games.brennan.dungeontrain.mixin.StructureTemplateAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;

/**
 * Resizes a Lost City piece's bounding box at structure-start time to what {@link LostCityStretchProcessor}
 * will place.
 *
 * <p>A piece's box is the template's, but a stretched building is wider, longer or taller than its template
 * and a shrunk one smaller. The box decides which chunks reference the start (a block outside it is never
 * placed) and where {@code BeardifierMixin} raises the ground to the pad, so it has to match: too small
 * clips the added bays at a chunk edge, too large raises a plateau under empty plaza. Each stretch
 * processor on the piece's element is asked for its {@link LostCityStretchProcessor#plan} — on the
 * template's own blocks, at the piece's position, exactly as placement will — and the box's far side along
 * the world direction of the template's axis moves by the plan's growth.</p>
 *
 * <p>Runs from {@code StructureBasementMixin} after Lost City Terrain Fit's seating, since the plan is keyed on
 * the piece's final position.</p>
 */
public final class LostCityFootprint {

    private LostCityFootprint() {}

    /** Resizes every piece of {@code start} that carries a stretch processor. */
    public static void resize(StructureStart start, StructureTemplateManager templates) {
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece pool) || !(pool.getElement() instanceof SinglePoolElement element)) continue;
            SinglePoolElementAccessor access = (SinglePoolElementAccessor) element;
            ResourceLocation id = access.dungeontrain$template().left().orElse(null);
            if (id == null) continue;
            List<StructureTemplate.StructureBlockInfo> originals = null;
            for (StructureProcessor processor : access.dungeontrain$processors().value().list()) {
                if (!(processor instanceof LostCityStretchProcessor stretch)) continue;
                if (originals == null) {
                    StructureTemplate template = templates.getOrCreate(id);
                    List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) template).dungeontrain$palettes();
                    if (palettes.isEmpty()) break;
                    originals = palettes.get(0).blocks();
                }
                LostCityStretchProcessor.Plan plan = stretch.plan(pool.getPosition(), originals);
                if (plan == null) continue;
                BoundingBox box = pool.getBoundingBox();
                ((StructurePieceAccessor) pool).dungeontrain$setBoundingBox(grown(box, stretch.axis(), pool.getRotation(), plan.growth()));
            }
        }
    }

    /**
     * {@code box} with its far side along the world direction of template axis {@code axis} (under
     * {@code rotation}, no mirror) moved by {@code growth} blocks — outward when positive, inward when
     * negative. Pure.
     */
    public static BoundingBox grown(BoundingBox box, Direction.Axis axis, Rotation rotation, int growth) {
        Vec3i dir = StructureTemplate.transform(BlockPos.ZERO.relative(axis, 1), Mirror.NONE, rotation, BlockPos.ZERO);
        int minX = box.minX(), minY = box.minY(), minZ = box.minZ(), maxX = box.maxX(), maxY = box.maxY(), maxZ = box.maxZ();
        if (dir.getX() > 0) maxX += growth; else if (dir.getX() < 0) minX -= growth;
        if (dir.getY() > 0) maxY += growth; else if (dir.getY() < 0) minY -= growth;
        if (dir.getZ() > 0) maxZ += growth; else if (dir.getZ() < 0) minZ -= growth;
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
