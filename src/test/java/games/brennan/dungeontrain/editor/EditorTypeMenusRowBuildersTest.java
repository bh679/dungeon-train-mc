package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The shared variant-row builders must have a body that returns.
 *
 * <p>Written after a scripted refactor replaced the bodies of {@code contentsRows} and
 * {@code trackKindRows} with calls to themselves. Every one of the suite's other tests stayed
 * green — nothing here exercises {@link EditorTypeMenus}, because the builders read live
 * registries — and the breakage surfaced only in game, as a {@code StackOverflowError} on the
 * server thread that killed the roster reply and, with it, the world-space Contents and Tracks
 * menus.</p>
 *
 * <p>Handing each builder an <b>empty</b> list is what makes this testable without a server: the
 * loop body never runs, so no registry is touched. What is asserted is therefore narrow and
 * deliberately honest — only that the call does not recurse forever. A builder that throws for
 * want of a registry passes, because that is not the failure being pinned.</p>
 */
final class EditorTypeMenusRowBuildersTest {

    /** Run {@code call}; fail only on the runaway recursion this test exists to catch. */
    private static void assertNoRunawayRecursion(String name, Supplier<List<EditorTypeMenusPacket.Variant>> call) {
        try {
            call.get();
        } catch (StackOverflowError overflow) {
            fail(name + " recursed until the stack ran out — its body is calling itself");
        } catch (Throwable missingRegistry) {
            // No server here, so a builder may legitimately fail to read weights or provenance.
        }
    }

    @Test
    @DisplayName("carriageRows returns rather than recursing")
    void carriageRows() {
        assertNoRunawayRecursion("carriageRows", () -> EditorTypeMenus.carriageRows(List.of()));
    }

    @Test
    @DisplayName("partRows returns rather than recursing")
    void partRows() {
        assertNoRunawayRecursion("partRows", () -> EditorTypeMenus.partRows(CarriagePartKind.FLOOR, List.of()));
    }

    @Test
    @DisplayName("contentsRows returns rather than recursing")
    void contentsRows() {
        assertNoRunawayRecursion("contentsRows", () -> EditorTypeMenus.contentsRows(List.of()));
    }

    @Test
    @DisplayName("trackKindRows returns rather than recursing, for track-side kinds and for rooms")
    void trackKindRows() {
        assertNoRunawayRecursion("trackKindRows(tracks)",
            () -> EditorTypeMenus.trackKindRows(TrackKind.TILE, List.of(), EditorCategory.TRACKS));
        assertNoRunawayRecursion("trackKindRows(portals)",
            () -> EditorTypeMenus.trackKindRows(TrackKind.PORTAL_ROOM, List.of(), EditorCategory.PORTALS));
    }
}
