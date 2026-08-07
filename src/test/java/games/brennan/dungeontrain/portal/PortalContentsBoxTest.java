package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The {@code portal} contents describe what stands inside a portal <i>corridor</i>, which is longer
 * than the carriage slot it is placed in — so their box is measured over the corridor, not the
 * carriage.
 *
 * <p>Three separate things have to agree on that figure or the feature quietly does nothing: the
 * size gate the template is loaded through, the editor plot it is authored in, and the bounds its
 * variant sidecar is filtered against. These pin the figure so a change to one has to be a change to
 * all.</p>
 */
class PortalContentsBoxTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;   // 9x7x7
    private static final CarriageContents PORTAL = PortalCarriageBuilder.portalContents();
    private static final CarriageContents ORDINARY = CarriageContents.custom("books");

    @Test
    @DisplayName("Portal contents are authored against the corridor: a 13-long box, 11x5x5 inside")
    void portalContents_useTheCorridorBox() {
        assertEquals(PortalCorridorSize.corridorDims(DIMS),
            CarriageContentsPlacer.contentsDims(PORTAL, DIMS));

        Vec3i interior = CarriageContentsPlacer.interiorSizeFor(PORTAL, DIMS);
        assertEquals(new Vec3i(11, 5, 5), interior);
    }

    @Test
    @DisplayName("Every other contents is unchanged — a plain carriage interior")
    void ordinaryContents_areUntouched() {
        assertEquals(DIMS, CarriageContentsPlacer.contentsDims(ORDINARY, DIMS));
        assertEquals(CarriageContentsPlacer.interiorSize(DIMS),
            CarriageContentsPlacer.interiorSizeFor(ORDINARY, DIMS));
        assertEquals(new Vec3i(7, 5, 5), CarriageContentsPlacer.interiorSizeFor(ORDINARY, DIMS));

        // The two boxes must genuinely differ, or none of this is doing anything.
        assertNotEquals(CarriageContentsPlacer.interiorSizeFor(ORDINARY, DIMS),
            CarriageContentsPlacer.interiorSizeFor(PORTAL, DIMS));
    }

    @Test
    @DisplayName("The corridor interior is the corridor box minus its shell, at every carriage length")
    void interiorTracksTheCorridorAtEveryLength() {
        for (int length = CarriageDims.MIN_LENGTH; length <= CarriageDims.MAX_LENGTH; length++) {
            CarriageDims dims = new CarriageDims(length, DIMS.width(), DIMS.height());
            CarriageDims corridor = PortalCorridorSize.corridorDims(dims);

            assertEquals(CarriageContentsPlacer.interiorSize(corridor),
                CarriageContentsPlacer.interiorSizeFor(PORTAL, dims),
                "length " + length);
        }
    }

    @Test
    @DisplayName("A sub-variant of the portal contents is measured as a corridor, like its parent")
    void subVariants_inheritTheCorridorBox() {
        CarriageContentsGroupStore.injectForTesting("portal",
            new CarriageContentsGroup(List.of(new CarriageContentsGroup.Member("portalzigzag", 1))));
        try {
            CarriageContents child = CarriageContents.custom("portalzigzag");

            // A sub-variant fills the same space as its parent — sized as a carriage it would get a
            // plot four blocks short, and the template captured there is rejected on load.
            assertEquals(PortalCorridorSize.corridorDims(DIMS),
                CarriageContentsPlacer.contentsDims(child, DIMS));
            assertEquals(new Vec3i(11, 5, 5),
                CarriageContentsPlacer.interiorSizeFor(child, DIMS));
        } finally {
            CarriageContentsGroupStore.invalidate("portal");
        }
    }

    @Test
    @DisplayName("Nesting is followed to the root, and a sub-variant of something else is untouched")
    void nestedGroups_resolveToTheRoot() {
        CarriageContentsGroupStore.injectForTesting("portal",
            new CarriageContentsGroup(List.of(new CarriageContentsGroup.Member("portalzigzag", 1))));
        CarriageContentsGroupStore.injectForTesting("portalzigzag",
            new CarriageContentsGroup(List.of(new CarriageContentsGroup.Member("portalzigzag_lit", 1))));
        CarriageContentsGroupStore.injectForTesting("books",
            new CarriageContentsGroup(List.of(new CarriageContentsGroup.Member("books_rare", 1))));
        try {
            assertEquals(PortalCorridorSize.corridorDims(DIMS),
                CarriageContentsPlacer.contentsDims(CarriageContents.custom("portalzigzag_lit"), DIMS),
                "a grandchild of portal is still a corridor");
            assertEquals(DIMS,
                CarriageContentsPlacer.contentsDims(CarriageContents.custom("books_rare"), DIMS),
                "a sub-variant of something else stays a carriage");
        } finally {
            CarriageContentsGroupStore.invalidate("portal");
            CarriageContentsGroupStore.invalidate("portalzigzag");
            CarriageContentsGroupStore.invalidate("books");
        }
    }

    @Test
    @DisplayName("A cycle in the group sidecars terminates instead of hanging the server")
    void cyclicGroups_doNotHang() {
        // findParentOf reads sidecars off disk and this runs per placement, so an authored cycle
        // has to be survivable, not merely unlikely.
        CarriageContentsGroupStore.injectForTesting("loop_a",
            new CarriageContentsGroup(List.of(new CarriageContentsGroup.Member("loop_b", 1))));
        CarriageContentsGroupStore.injectForTesting("loop_b",
            new CarriageContentsGroup(List.of(new CarriageContentsGroup.Member("loop_a", 1))));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertEquals(DIMS,
                    CarriageContentsPlacer.contentsDims(CarriageContents.custom("loop_a"), DIMS)));
        } finally {
            CarriageContentsGroupStore.invalidate("loop_a");
            CarriageContentsGroupStore.invalidate("loop_b");
        }
    }

    @Test
    @DisplayName("contentsDims resolves from the world's dims — feeding it its own output grows twice")
    void isNotIdempotent_soCallersMustPassWorldDims() {
        CarriageDims once = CarriageContentsPlacer.contentsDims(PORTAL, DIMS);
        CarriageDims twice = CarriageContentsPlacer.contentsDims(PORTAL, once);

        // Not a wish — a warning. This documents why every (contents, dims) method resolves the box
        // itself and callers hand it the world's dims, never an already-resolved one.
        assertNotEquals(once, twice);
        assertEquals(13, once.length());
        assertEquals(19, twice.length());
    }
}
