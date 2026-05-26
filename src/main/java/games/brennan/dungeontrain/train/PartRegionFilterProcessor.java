package games.brennan.dungeontrain.train;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantPartsStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link StructureProcessor} that drops base-shell template blocks whose
 * target world position falls inside a cell that the parts overlay will
 * subsequently stamp. Wired into {@code CarriagePlacer.stampBase} so the
 * base never lays down blocks that {@code CarriagePartPlacer} would have to
 * pre-erase before stamping the part template on top.
 *
 * <p>The pre-erase that previously lived in
 * {@code CarriagePartPlacer.placeAtPerPlacement} called
 * {@code level.setBlock(pos, AIR, 3)} cell-by-cell — flag 3 includes
 * {@code UPDATE_NEIGHBORS}, so the shape-update cascade destroyed any
 * 2-tall paired-half block (doors, tall flowers, beds) the base had
 * stamped, dropping it as an item via
 * {@code Block.updateOrDestroy → destroyBlock(dropBlock=true)}. By
 * preventing the base from stamping those cells in the first place there
 * is nothing to erase, so the cascade never fires.</p>
 *
 * <p>Which cells are claimed is determined per-placement (not per-kind):
 * a placement contributes its bounding box to the claimed set iff its
 * pick is non-{@link CarriagePartKind#NONE} AND its template file is
 * present on disk. That predicate exactly mirrors the gate inside
 * {@code CarriagePlacer.stampPartsOverlay} so the base never strips a
 * cell the overlay will then fail to fill.</p>
 */
public final class PartRegionFilterProcessor extends StructureProcessor {

    /**
     * Runtime-only processor — never serialised, so the codec just produces
     * a sentinel empty instance on decode and writes nothing on encode.
     */
    private static final StructureProcessorType<PartRegionFilterProcessor> TYPE =
        () -> MapCodec.unit(new PartRegionFilterProcessor(BlockPos.ZERO, List.of()));

    private final BlockPos carriageOrigin;
    private final List<BoundingBox> claimedLocalBoxes;

    private PartRegionFilterProcessor(BlockPos carriageOrigin, List<BoundingBox> claimedLocalBoxes) {
        this.carriageOrigin = carriageOrigin;
        this.claimedLocalBoxes = claimedLocalBoxes;
    }

    /**
     * Build the processor for {@code variant} at {@code carriageOrigin}, or
     * return {@link Optional#empty()} when the variant has no parts assignment
     * (in which case the base stamps the full footprint unfiltered, matching
     * the legacy non-parts behaviour). Caller passes the same
     * {@code (seed, carriageIndex, flatbedAtBack, flatbedAtFront)} args that
     * {@code stampPartsOverlay} uses so the claimed-box set matches the
     * picks the overlay will actually stamp.
     */
    public static Optional<PartRegionFilterProcessor> forVariant(
        ServerLevel level, BlockPos carriageOrigin,
        CarriageVariant variant, CarriageDims dims,
        long seed, int carriageIndex,
        boolean flatbedAtBack, boolean flatbedAtFront
    ) {
        Optional<CarriagePartAssignment> assignment = CarriageVariantPartsStore.get(variant);
        if (assignment.isEmpty()) return Optional.empty();
        CarriagePartAssignment a = assignment.get();
        if (a.allNone()) return Optional.empty();

        List<BoundingBox> boxes = new ArrayList<>();
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            List<CarriagePartKind.Placement> placements = kind.placements(dims);
            Vec3i partSize = kind.dims(dims);
            List<String> picks = a.pickPerPlacement(
                kind, seed, carriageIndex, flatbedAtBack, flatbedAtFront);
            for (int i = 0; i < placements.size(); i++) {
                String name = i < picks.size() ? picks.get(i) : null;
                if (name == null || name.isBlank() || CarriagePartKind.NONE.equals(name)) continue;
                if (CarriagePartTemplateStore.get(level, kind, name, dims).isEmpty()) continue;
                BlockPos off = placements.get(i).originOffset();
                boxes.add(new BoundingBox(
                    off.getX(), off.getY(), off.getZ(),
                    off.getX() + partSize.getX() - 1,
                    off.getY() + partSize.getY() - 1,
                    off.getZ() + partSize.getZ() - 1));
            }
        }
        if (boxes.isEmpty()) return Optional.empty();
        return Optional.of(new PartRegionFilterProcessor(carriageOrigin, boxes));
    }

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader level, BlockPos origin, BlockPos pivot,
        StructureTemplate.StructureBlockInfo source,
        StructureTemplate.StructureBlockInfo target,
        StructurePlaceSettings settings
    ) {
        int lx = target.pos().getX() - carriageOrigin.getX();
        int ly = target.pos().getY() - carriageOrigin.getY();
        int lz = target.pos().getZ() - carriageOrigin.getZ();
        for (BoundingBox box : claimedLocalBoxes) {
            if (lx >= box.minX() && lx <= box.maxX()
                && ly >= box.minY() && ly <= box.maxY()
                && lz >= box.minZ() && lz <= box.maxZ()) {
                return null;
            }
        }
        return target;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }

    /** Test hook — exposes the claimed-box count without leaking internal state. */
    public int claimedBoxCountForTest() {
        return claimedLocalBoxes.size();
    }

    /** Test hook — runs the same containment check the processor uses. */
    public boolean isLocalPosClaimedForTest(int lx, int ly, int lz) {
        for (BoundingBox box : claimedLocalBoxes) {
            if (lx >= box.minX() && lx <= box.maxX()
                && ly >= box.minY() && ly <= box.maxY()
                && lz >= box.minZ() && lz <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /** Test-only factory bypassing the disk/store predicates. */
    public static PartRegionFilterProcessor forTest(BlockPos carriageOrigin, List<BoundingBox> boxes) {
        return new PartRegionFilterProcessor(carriageOrigin, List.copyOf(boxes));
    }
}
