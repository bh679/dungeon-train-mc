package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression guard for the parts/contents plot overlap that produced the
 * {@code dev/parts-content-bleed-fix} bug — content blocks (barrels,
 * 2ndlevel platforms) appearing inside the FLOOR parts editor's air column.
 *
 * <p>Original bug: parts shared {@code Y=250} with carriages, contents,
 * and tracks. {@code CarriagePartTemplate.eraseAt} only clears the kind's
 * exact footprint Y range (FLOOR = {@code Y=250} only), so content
 * interior blocks at {@code Y>=251} survived a parts-editor enter and
 * were visible inside the FLOOR plot's air column.</p>
 *
 * <p>Fix: parts moved to {@code Y=400}. The Y separation makes overlap
 * with any other editor plot (all at {@code Y~250..283}) impossible. This
 * test asserts the AABB-disjointness invariant at min/default/max dims so
 * any future change that brings parts back down into the 250-range will
 * fail the test before it reaches a player.</p>
 */
final class CarriagePartEditorPlotLayoutTest {

    private static final String[] FLOOR_NAMES = {"cracked", "standard", "wood"};
    private static final String[] WALLS_NAMES = {"cracked", "standard"};
    private static final String[] ROOF_NAMES = {"standard", "windowed"};
    private static final String[] DOORS_NAMES = {"irondoor", "standard", "wooddoor"};

    private static final String[] CONTENTS_CUSTOM_NAMES = {
        "2ndlevel", "barrel", "books", "chest", "craft", "enchant",
        "ender", "kitchen", "maze", "sand", "smelt", "upgrade", "vase"
    };

    @BeforeEach
    void registerFixtures() {
        CarriageContentsRegistry.clear();
        CarriagePartRegistry.clear();
        for (String n : CONTENTS_CUSTOM_NAMES) {
            CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom(n));
        }
        for (String n : FLOOR_NAMES) CarriagePartRegistry.register(CarriagePartKind.FLOOR, n);
        for (String n : WALLS_NAMES) CarriagePartRegistry.register(CarriagePartKind.WALLS, n);
        for (String n : ROOF_NAMES) CarriagePartRegistry.register(CarriagePartKind.ROOF, n);
        for (String n : DOORS_NAMES) CarriagePartRegistry.register(CarriagePartKind.DOORS, n);
    }

    @AfterEach
    void restoreEmpty() {
        CarriageContentsRegistry.clear();
        CarriagePartRegistry.clear();
    }

    @Test
    @DisplayName("default dims: no parts plot AABB intersects any contents plot AABB")
    void defaultDims_noOverlap() {
        assertNoOverlap(CarriageDims.DEFAULT);
    }

    @Test
    @DisplayName("max dims: no parts plot AABB intersects any contents plot AABB")
    void maxDims_noOverlap() {
        CarriageDims max = new CarriageDims(
            CarriageDims.MAX_LENGTH, CarriageDims.MAX_WIDTH, CarriageDims.MAX_HEIGHT);
        assertNoOverlap(max);
    }

    @Test
    @DisplayName("min dims: no parts plot AABB intersects any contents plot AABB")
    void minDims_noOverlap() {
        CarriageDims min = new CarriageDims(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
        assertNoOverlap(min);
    }

    /**
     * For every (part kind, registered name) build the plot AABB and check
     * it doesn't intersect any (contents) plot AABB. Reports the first
     * collision with full coordinates for triage.
     */
    private void assertNoOverlap(CarriageDims dims) {
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            for (String name : CarriagePartRegistry.registeredNames(kind)) {
                BlockPos partOrigin = CarriagePartEditor.plotOrigin(kind, name, dims);
                if (partOrigin == null) continue;
                Vec3i partSize = kind.dims(dims);
                for (CarriageContents c : CarriageContentsRegistry.allContents()) {
                    BlockPos contentsOrigin = CarriageContentsEditor.plotOrigin(c, dims);
                    if (contentsOrigin == null) continue;
                    Vec3i contentsSize = new Vec3i(dims.length(), dims.height(), dims.width());
                    if (aabbIntersect(partOrigin, partSize, contentsOrigin, contentsSize)) {
                        fail(String.format(
                            "Plot overlap at dims L=%d W=%d H=%d: part %s:%s @ %s size %s "
                                + "intersects contents %s @ %s size %s",
                            dims.length(), dims.width(), dims.height(),
                            kind.id(), name, partOrigin, partSize,
                            c.id(), contentsOrigin, contentsSize));
                    }
                }
            }
        }
    }

    /**
     * Half-open AABB intersection test: A intersects B iff
     * {@code A.min < B.max && A.max > B.min} on every axis. Plot footprints
     * occupy {@code [origin, origin+size)} — touching edges (e.g. one plot
     * ending at Z=44 and another starting at Z=44) do not count as overlap.
     */
    private static boolean aabbIntersect(BlockPos aMin, Vec3i aSize, BlockPos bMin, Vec3i bSize) {
        int aMaxX = aMin.getX() + aSize.getX();
        int aMaxY = aMin.getY() + aSize.getY();
        int aMaxZ = aMin.getZ() + aSize.getZ();
        int bMaxX = bMin.getX() + bSize.getX();
        int bMaxY = bMin.getY() + bSize.getY();
        int bMaxZ = bMin.getZ() + bSize.getZ();
        return aMin.getX() < bMaxX && aMaxX > bMin.getX()
            && aMin.getY() < bMaxY && aMaxY > bMin.getY()
            && aMin.getZ() < bMaxZ && aMaxZ > bMin.getZ();
    }
}
