package games.brennan.dungeontrain.builder.structure;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which builds may have their own blocks stand in for the template they came from.
 *
 * <p>The rule that decides whether instant updates are correct or a hole in the world, so it is
 * pinned as a statement of each case rather than as a regression on the current answer.</p>
 */
final class BuilderOpenTemplateTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final String VARIANT = "ore_cart";

    private static BuilderStructureNeeds.Context carriage(BuilderMode mode,
                                                          BuilderNewOptions.SubType subType,
                                                          int parked) {
        return new BuilderStructureNeeds.Context(mode, subType, null, "some_room", DIMS, parked,
                null, null, VARIANT);
    }

    private static BuilderStructureNeeds.Context track(TrackKind kind, String name) {
        return new BuilderStructureNeeds.Context(BuilderMode.TRACKS_TUNNELS, null, kind,
                name, DIMS, 0, null, null, VARIANT);
    }

    @Test
    @DisplayName("A carriage build stands in for the carriage it was stamped from")
    void wholeCarriageSubstitutes() {
        BuilderOpenTemplate open = BuilderStructureNeeds.openTemplateFor(
                carriage(BuilderMode.INSIDE_CARRIAGE,
                        BuilderNewOptions.SubType.WHOLE_CARRIAGE, 1));

        assertEquals(new BuilderOpenTemplate.OpenCarriage(VARIANT), open);
        assertTrue(open.matchesCarriage(VARIANT));
        assertFalse(open.matchesCarriage("something_else"),
                "a neighbouring slot of another variant is not this build");
    }

    @Test
    @DisplayName("A carriage room seen from inside does NOT, because its shell is cleared out")
    void carriageRoomInsideDoesNotSubstitute() {
        // The one case that would corrupt itself. `insideCarriage` declares a CarriageShell here,
        // `allows` exempts that shell from the build-volume filter, and the materialiser therefore
        // writes AIR over it whenever the structures are not solid. Read the volume back then and
        // there is no shell in it — so a carriage resolved from it would have no walls, and the
        // shell resolved from *that* would have no cells at all. A hole that re-opens every sweep.
        assertNull(BuilderStructureNeeds.openTemplateFor(
                carriage(BuilderMode.INSIDE_CARRIAGE,
                        BuilderNewOptions.SubType.CARRIAGE_ROOM, 1)));
    }

    @Test
    @DisplayName("The same room seen from outside does, because no shell is declared there")
    void carriageRoomOutsideSubstitutes() {
        // Same sub type, different mode, and the difference is exactly whether a shell is declared.
        // That is why the rule is written against the shell rather than against the sub type.
        assertEquals(new BuilderOpenTemplate.OpenCarriage(VARIANT),
                BuilderStructureNeeds.openTemplateFor(
                        carriage(BuilderMode.TRAIN_OUTSIDE,
                                BuilderNewOptions.SubType.CARRIAGE_ROOM, 1)));
    }

    @Test
    @DisplayName("A part build substitutes — the volume is a whole carriage whatever is being authored")
    void partsSubstitute() {
        assertEquals(new BuilderOpenTemplate.OpenCarriage(VARIANT),
                BuilderStructureNeeds.openTemplateFor(
                        carriage(BuilderMode.INSIDE_CARRIAGE,
                                BuilderNewOptions.SubType.PARTS, 1)));
    }

    @Test
    @DisplayName("A track build needs both halves to match, since `default` exists under every kind")
    void trackNeedsKindAndName() {
        BuilderOpenTemplate open =
                BuilderStructureNeeds.openTemplateFor(track(TrackKind.TILE, "sleepside"));

        assertEquals(new BuilderOpenTemplate.OpenTrack(TrackKind.TILE, "sleepside"), open);
        assertTrue(open.matchesTrack(TrackKind.TILE, "sleepside"));
        assertFalse(open.matchesTrack(TrackKind.TILE, "default"),
                "a tile the seed rolled to another variant is not this build");
        assertFalse(open.matchesTrack(TrackKind.PILLAR_TOP, "sleepside"),
                "the same name under another kind is a different template");
        assertFalse(open.matchesCarriage(VARIANT), "a track build is not a carriage");
    }

    @Test
    @DisplayName("Nothing to stand in for: no name, no variant, or a portal room")
    void nullWhereThereIsNoStoredTemplate() {
        assertNull(BuilderStructureNeeds.openTemplateFor(track(TrackKind.TILE, "")),
                "an unnamed draft has no stored template to be");
        assertNull(BuilderStructureNeeds.openTemplateFor(
                new BuilderStructureNeeds.Context(BuilderMode.TRACKS_TUNNELS, null, null,
                        "default", DIMS, 0, null, null, VARIANT)),
                "the track mode is open but nothing is on the plot");
        assertNull(BuilderStructureNeeds.openTemplateFor(
                new BuilderStructureNeeds.Context(BuilderMode.INSIDE_CARRIAGE,
                        BuilderNewOptions.SubType.WHOLE_CARRIAGE, null, "some_room", DIMS, 1,
                        null, null, "")),
                "no variant means nothing was stamped to stand in for");
        assertNull(BuilderStructureNeeds.openTemplateFor(
                new BuilderStructureNeeds.Context(BuilderMode.TRAIN_DIMENSIONS, null, null,
                        "hall", DIMS, 0, PortalRoomMode.DEFAULT, new Vec3i(11, 7, 13), VARIANT)),
                "a room's copies read the open build directly and never go through a store");
        assertNull(BuilderStructureNeeds.openTemplateFor(null));
    }

    @Test
    @DisplayName("While a track template is open, every slot of its kind is it rather than a roll")
    void anOpenTrackKindLocksItsSlots() {
        // The bug this exists to fix. The line rolls a variant per tile off the world seed, and four
        // tile variants ship — so with a tile open, three slots in four were somebody else's
        // template and correctly refused to follow the edit. Pillars only looked fine because one
        // variant of each ships, so every roll already came back as the one being edited.
        BuilderStructureCells.Sources locked = sources(
                new BuilderOpenTemplate.OpenTrack(TrackKind.TILE, "sleepside"), false);

        assertEquals("sleepside", locked.lockedName(TrackKind.TILE));
        assertNull(locked.lockedName(TrackKind.PILLAR_TOP),
                "a kind you are not editing still rolls, which is the line the world builds");
    }

    @Test
    @DisplayName("The lock is the scene's business, so cycling Updates does not change it")
    void theLockIsIndependentOfTheRefreshSetting() {
        // Whether the run shows the stored tile or the one being edited is the control's question.
        // Whether the run is made of that tile at all is not, and must not shift under the player
        // when they cycle Updates.
        BuilderOpenTemplate open = new BuilderOpenTemplate.OpenTrack(TrackKind.TILE, "sleepside");

        assertEquals("sleepside", sources(open, true).lockedName(TrackKind.TILE));
        assertEquals("sleepside", sources(open, false).lockedName(TrackKind.TILE));
        // Substitution, though, is exactly the control's question.
        assertTrue(sources(open, true).substitutesTrack(TrackKind.TILE, "sleepside"));
        assertFalse(sources(open, false).substitutesTrack(TrackKind.TILE, "sleepside"));
    }

    @Test
    @DisplayName("A carriage build locks nothing — a carriage has no rolled slots to lock")
    void aCarriageBuildLocksNoTrackSlots() {
        BuilderStructureCells.Sources src =
                sources(new BuilderOpenTemplate.OpenCarriage(VARIANT), true);
        for (TrackKind kind : TrackKind.values()) {
            assertNull(src.lockedName(kind));
        }
        assertNull(sources(null, false).lockedName(TrackKind.TILE));
    }

    private static BuilderStructureCells.Sources sources(BuilderOpenTemplate open,
                                                         boolean substitute) {
        return new BuilderStructureCells.Sources(null, DIMS, 1234L, java.util.Map.of(),
                Vec3i.ZERO, open, substitute);
    }

    @Test
    @DisplayName("A carriage identity never matches a track read, and the reverse")
    void identitiesDoNotCrossOver() {
        BuilderOpenTemplate carriage = new BuilderOpenTemplate.OpenCarriage(VARIANT);
        BuilderOpenTemplate track = new BuilderOpenTemplate.OpenTrack(TrackKind.TILE, "default");

        assertFalse(carriage.matchesTrack(TrackKind.TILE, VARIANT));
        assertFalse(track.matchesCarriage("default"));
        // An empty id must never match an empty read, or every unnamed thing would be every other.
        assertFalse(new BuilderOpenTemplate.OpenCarriage("").matchesCarriage(""));
        assertFalse(new BuilderOpenTemplate.OpenTrack(TrackKind.TILE, "").matchesTrack(
                TrackKind.TILE, ""));
    }
}
