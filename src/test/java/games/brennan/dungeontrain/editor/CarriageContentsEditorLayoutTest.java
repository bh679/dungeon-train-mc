package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the contents editor's 2D plot layout: top-level variants sit in
 * a +X row at {@code z = PLOT_Z}, sub-variants stack along +Z from their
 * parent's slot sharing the parent's X.
 */
final class CarriageContentsEditorLayoutTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @BeforeEach
    void cleanSlate() {
        CarriageContentsRegistry.clear();
        CarriageContentsGroupStore.clearCache();
    }

    @AfterEach
    void restoreEmpty() {
        CarriageContentsRegistry.clear();
        CarriageContentsGroupStore.clearCache();
    }

    private static void registerCustom(String id) {
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom(id));
    }

    private static CarriageContentsGroup group(String... idsAndWeights) {
        List<CarriageContentsGroup.Member> ms = new ArrayList<>();
        for (int i = 0; i < idsAndWeights.length; i += 2) {
            int w = Integer.parseInt(idsAndWeights[i + 1]);
            ms.add(new CarriageContentsGroup.Member(idsAndWeights[i], w));
        }
        return new CarriageContentsGroup(ms);
    }

    private static BlockPos plot(String id) {
        return CarriageContentsEditor.plotOrigin(CarriageContents.custom(id), DIMS);
    }

    @Test
    @DisplayName("No groups: top-level variants line up along +X at z=PLOT_Z")
    void noGroups_topLevelRow() {
        registerCustom("alpha");
        registerCustom("beta");
        int xStep = DIMS.length() + EditorLayout.GAP;

        BlockPos alpha = plot("alpha");
        BlockPos beta = plot("beta");
        assertNotNull(alpha);
        assertNotNull(beta);
        // Registry order is [default, alpha, beta]; alpha = slot 1, beta = slot 2.
        assertEquals(xStep, alpha.getX());
        assertEquals(2 * xStep, beta.getX());
        // Same Y, same Z for top-level variants.
        assertEquals(alpha.getY(), beta.getY());
        assertEquals(alpha.getZ(), beta.getZ());
    }

    @Test
    @DisplayName("Sub-variants share parent's X and step along +Z")
    void subVariants_columnZ() {
        registerCustom("container");
        registerCustom("container_wooden");
        registerCustom("container_metal");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1", "container_metal", "1"));

        BlockPos parent = plot("container");
        BlockPos wooden = plot("container_wooden");
        BlockPos metal = plot("container_metal");
        assertNotNull(parent);
        assertNotNull(wooden);
        assertNotNull(metal);

        // Same X as parent.
        assertEquals(parent.getX(), wooden.getX());
        assertEquals(parent.getX(), metal.getX());
        // Same Y.
        assertEquals(parent.getY(), wooden.getY());
        assertEquals(parent.getY(), metal.getY());

        // Z step = width + SUB_VARIANT_GAP, member at index i sits (i+1) steps below parent.
        int zStep = DIMS.width() + EditorLayout.SUB_VARIANT_GAP;
        assertEquals(parent.getZ() + zStep, wooden.getZ());
        assertEquals(parent.getZ() + 2 * zStep, metal.getZ());
    }

    @Test
    @DisplayName("Children excluded from top-level row: parent's X position unaffected by member count")
    void children_excludedFromTopLevelRow() {
        registerCustom("alpha");
        registerCustom("container");
        registerCustom("zulu");
        // Compute baseline positions without a group.
        BlockPos zuluBefore = plot("zulu");

        // Add members; zulu's slot must NOT shift along +X.
        registerCustom("container_wooden");
        registerCustom("container_metal");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1", "container_metal", "1"));

        BlockPos zuluAfter = plot("zulu");
        assertEquals(zuluBefore, zuluAfter,
            "zulu's +X slot must not shift when group members are added (children excluded from top-level row)");
    }

    @Test
    @DisplayName("plotOrigin returns null for an unregistered id")
    void plotOrigin_unregistered_null() {
        assertNull(plot("never_registered"));
    }

    @Test
    @DisplayName("plotOrigin is stable across multiple calls for the same id")
    void plotOrigin_isStable() {
        registerCustom("container");
        registerCustom("container_wooden");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1"));

        BlockPos a = plot("container_wooden");
        BlockPos b = plot("container_wooden");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("Multiple groups: each parent has its own +Z column at its own +X slot")
    void multipleGroups_independentColumns() {
        registerCustom("alpha");
        registerCustom("alpha_a");
        registerCustom("beta");
        registerCustom("beta_b");
        CarriageContentsGroupStore.injectForTesting("alpha", group("alpha_a", "1"));
        CarriageContentsGroupStore.injectForTesting("beta", group("beta_b", "1"));

        BlockPos alpha = plot("alpha");
        BlockPos alphaA = plot("alpha_a");
        BlockPos beta = plot("beta");
        BlockPos betaB = plot("beta_b");

        // Each child shares its parent's X but is offset in +Z.
        assertEquals(alpha.getX(), alphaA.getX());
        assertEquals(beta.getX(), betaB.getX());
        assertTrue(alphaA.getZ() > alpha.getZ());
        assertTrue(betaB.getZ() > beta.getZ());
        // Parents have different X slots.
        assertTrue(alpha.getX() != beta.getX());
    }
}
