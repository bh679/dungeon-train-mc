package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the builder says goes around a build.
 *
 * <p>Worth pinning hard, because this class exists to make a family of bugs unreachable rather than
 * merely fixed. Each of them was a wrong answer to "what surrounds this?", and each is now a
 * statement this test can hold to:</p>
 *
 * <ul>
 *   <li>a track build declares no carriage — the leak that stood a train up in the middle of one;</li>
 *   <li>asking twice gives the same answer — the re-read that used to destroy the record;</li>
 *   <li>the modes that park no carriage still declare their own scenery — the carriage-count gate
 *       that made the control inert in two of the four modes.</li>
 * </ul>
 */
final class BuilderSurroundingsTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    private static BuilderSurroundings.Context carriageRoom() {
        return new BuilderSurroundings.Context(BuilderMode.INSIDE_CARRIAGE,
                BuilderNewOptions.SubType.CARRIAGE_ROOM, "my_room", DIMS, 1,
                null, null, null, "standard");
    }

    private static BuilderSurroundings.Context wholeGroup() {
        return new BuilderSurroundings.Context(BuilderMode.TRAIN_OUTSIDE,
                BuilderNewOptions.SubType.WHOLE_CARRIAGE, "my_carriage", DIMS,
                BuilderWorldLayout.OUTSIDE_CARRIAGES, null, null, null, "standard");
    }

    private static BuilderSurroundings.Context trackBuild() {
        return new BuilderSurroundings.Context(BuilderMode.TRACKS_TUNNELS, null, "my_tile", DIMS, 0,
                null, null, TrackKind.TILE, null);
    }

    private static <T> List<T> of(List<BuilderSurroundings.Surround> all, Class<T> type) {
        return all.stream().map(BuilderSurroundings.Surround::source)
                .filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    @DisplayName("A track build declares no carriage — the leak that put a train on a track plot")
    void aTrackBuildHasNoTrain() {
        List<BuilderSurroundings.Surround> around = BuilderSurroundings.around(trackBuild());

        assertTrue(of(around, BuilderSurroundings.Source.Carriage.class).isEmpty());
        assertTrue(of(around, BuilderSurroundings.Source.CarriageSkin.class).isEmpty());
        assertTrue(of(around, BuilderSurroundings.Source.FlatbedPad.class).isEmpty());
        assertEquals(1, of(around, BuilderSurroundings.Source.TrackScene.class).size(),
                "a track build is judged against the line, so it declares the scene");
    }

    @Test
    @DisplayName("Asking twice gives the same answer — recomputing can't destroy it")
    void theDeclarationIsIdempotent() {
        BuilderSurroundings.Context ctx = carriageRoom();
        assertEquals(BuilderSurroundings.around(ctx), BuilderSurroundings.around(ctx));
        // And a third time, after the second: the old mechanism broke on exactly this shape.
        assertEquals(BuilderSurroundings.around(ctx), BuilderSurroundings.around(ctx));
    }

    @Test
    @DisplayName("A carriage room is surrounded by its own shell, two neighbours, two pads and the line")
    void aCarriageRoomIsInsideATrain() {
        List<BuilderSurroundings.Surround> around = BuilderSurroundings.around(carriageRoom());

        assertEquals(1, of(around, BuilderSurroundings.Source.CarriageSkin.class).size(),
                "the carriage you stand in");
        assertEquals(BuilderWorldLayout.OUTSIDE_CARRIAGES - 1,
                of(around, BuilderSurroundings.Source.Carriage.class).size(),
                "every slot in the group but the one you are parked in");
        assertEquals(2, of(around, BuilderSurroundings.Source.FlatbedPad.class).size());
        assertEquals(1, of(around, BuilderSurroundings.Source.TrackCorridor.class).size());
    }

    @Test
    @DisplayName("A full group declares its pads but no empty slot")
    void aFullGroupHasNothingMissing() {
        List<BuilderSurroundings.Surround> around = BuilderSurroundings.around(wholeGroup());

        assertTrue(of(around, BuilderSurroundings.Source.Carriage.class).isEmpty(),
                "nothing is missing from a full group");
        assertTrue(of(around, BuilderSurroundings.Source.CarriageSkin.class).isEmpty(),
                "a whole carriage IS the build; its shell is not scenery around it");
        assertEquals(2, of(around, BuilderSurroundings.Source.FlatbedPad.class).size(),
                "the pads frame the group whatever is parked in it");
    }

    @Test
    @DisplayName("Parts keeps its shell: a part is a piece of the shell, so the shell is the build")
    void partsOwnsItsShell() {
        BuilderSurroundings.Context parts = new BuilderSurroundings.Context(
                BuilderMode.INSIDE_CARRIAGE, BuilderNewOptions.SubType.PARTS, "my_part", DIMS, 1,
                null, null, null, "standard");

        assertTrue(of(BuilderSurroundings.around(parts),
                BuilderSurroundings.Source.CarriageSkin.class).isEmpty());
    }

    @Test
    @DisplayName("No surround ever lands on the carriage slot the build is parked in")
    void nothingIsDeclaredOnTopOfTheBuild() {
        int parkedMinX = BuilderBounds.buildVolumes(1, DIMS).get(0).minX();

        for (BuilderSurroundings.Surround s : BuilderSurroundings.around(carriageRoom())) {
            if (s.source() instanceof BuilderSurroundings.Source.Carriage) {
                assertFalse(s.origin().getX() == parkedMinX,
                        "a whole carriage was declared on top of the one being edited");
            }
        }
    }

    // ---- the skin rule: what surrounds you and what you save cannot overlap ----

    private static Stream<CarriageDims> dimensions() {
        return Stream.of(CarriageDims.DEFAULT,
                new CarriageDims(13, 9, 9),
                new CarriageDims(CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH,
                        CarriageDims.MIN_HEIGHT));
    }

    @Test
    @DisplayName("Nothing the carriage skin covers is inside the box a carriage room is saved from")
    void theSkinNeverOverlapsTheSavedRoom() {
        dimensions().forEach(dims -> {
            BlockPos interiorMin = CarriageContentsPlacer.interiorOrigin(BlockPos.ZERO);
            Vec3i interior = CarriageContentsPlacer.interiorSize(dims);

            for (int x = 0; x < dims.length(); x++) {
                for (int y = 0; y < dims.height(); y++) {
                    for (int z = 0; z < dims.width(); z++) {
                        if (!BuilderSurroundings.onSkin(x, y, z, dims)) {
                            continue;
                        }
                        boolean insideSavedRoom =
                                x >= interiorMin.getX() && x < interiorMin.getX() + interior.getX()
                                && y >= interiorMin.getY() && y < interiorMin.getY() + interior.getY()
                                && z >= interiorMin.getZ() && z < interiorMin.getZ() + interior.getZ();
                        assertFalse(insideSavedRoom,
                                "skin cell " + x + "," + y + "," + z + " at " + dims
                                        + " is inside what the room saves");
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("Skin and room together are the whole carriage, with no cell in both")
    void skinAndRoomPartitionTheCarriage() {
        dimensions().forEach(dims -> {
            Vec3i interior = CarriageContentsPlacer.interiorSize(dims);
            int cells = dims.length() * dims.height() * dims.width();
            int room = interior.getX() * interior.getY() * interior.getZ();

            int skin = 0;
            for (int x = 0; x < dims.length(); x++) {
                for (int y = 0; y < dims.height(); y++) {
                    for (int z = 0; z < dims.width(); z++) {
                        if (BuilderSurroundings.onSkin(x, y, z, dims)) {
                            skin++;
                        }
                    }
                }
            }
            assertEquals(cells - room, skin, "skin + room should be the carriage exactly once at " + dims);
        });
    }
}
