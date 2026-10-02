package games.brennan.dungeontrain.cheat;

import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.tools.BundledFingerprints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An accepted building is not custom content; everything else still is.
 *
 * <p>The edges matter in both directions. Too narrow and a player who downloaded a building the game
 * took on plays in Free Play for it. Too wide and a file that merely sits beside one — or a building
 * that has been changed since it was accepted — rides in on its exemption.</p>
 */
class ApprovedBuildingsTest {

    private static final Path SHIPPED =
        RepoPaths.resources().resolve("data/dungeontrain/structure/lost_city");

    @AfterEach
    void forget() {
        ApprovedBuildings.setAcceptedForTest(null);
    }

    /** A package folder holding one building, copied from a shipped one, and its weight sidecar. */
    private static Path packageWith(Path tmp, String name, String shipped) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("user"));
        Path buildings = Files.createDirectories(pkg.resolve("buildings"));
        Files.copy(SHIPPED.resolve(shipped + ".nbt"), buildings.resolve(name + ".nbt"));
        Files.writeString(buildings.resolve(name + ".building.json"), "{\"weight\": 3}");
        return pkg;
    }

    private static String hashOf(Path pkg, String name) throws IOException {
        return BundledFingerprints.hashOf(pkg.resolve("buildings").resolve(name + ".nbt"));
    }

    @Test
    @DisplayName("only a file filed under a building's name, directly in buildings/, is a building's file")
    void namesABuildingsFiles(@TempDir Path tmp) {
        Path pkg = tmp.resolve("user");
        assertEquals("clock_tower", ApprovedBuildings.buildingNameOf(pkg, pkg.resolve("buildings/clock_tower.nbt")));
        assertEquals("clock_tower", ApprovedBuildings.buildingNameOf(pkg, pkg.resolve("buildings/clock_tower.building.json")));
        assertEquals("clock_tower", ApprovedBuildings.buildingNameOf(pkg, pkg.resolve("buildings/clock_tower.png")));

        assertNull(ApprovedBuildings.buildingNameOf(pkg, pkg.resolve("templates/clock_tower.nbt")), "another kind");
        assertNull(ApprovedBuildings.buildingNameOf(pkg, pkg.resolve("buildings/deep/clock_tower.nbt")), "nested");
        assertNull(ApprovedBuildings.buildingNameOf(pkg, pkg.resolve("buildings/Clock Tower.nbt")), "not a building name");
        assertNull(ApprovedBuildings.buildingNameOf(pkg, pkg.resolve("buildings/.hidden")));
        assertNull(ApprovedBuildings.buildingNameOf(pkg, tmp.resolve("elsewhere/buildings/clock_tower.nbt")));
        assertNull(ApprovedBuildings.buildingNameOf(pkg, null));
    }

    @Test
    @DisplayName("a building is custom content until the relay's list names its hash")
    void acceptedBuildingIsNotCustomContent(@TempDir Path tmp) throws IOException {
        Path pkg = packageWith(tmp, "clock_tower", "water_tower");

        assertTrue(EditorContentIntegrity.containsAnyFile(pkg), "no list yet: nothing is exempt");

        ApprovedBuildings.setAcceptedForTest(Set.of());
        assertTrue(EditorContentIntegrity.containsAnyFile(pkg), "an empty list accepts nothing");

        ApprovedBuildings.setAcceptedForTest(Set.of(hashOf(pkg, "clock_tower")));
        assertFalse(EditorContentIntegrity.containsAnyFile(pkg), "accepted, sidecar and all");
    }

    @Test
    @DisplayName("an accepted building exempts itself, not its neighbours")
    void exemptionIsPerBuilding(@TempDir Path tmp) throws IOException {
        Path pkg = packageWith(tmp, "clock_tower", "water_tower");
        ApprovedBuildings.setAcceptedForTest(Set.of(hashOf(pkg, "clock_tower")));
        assertFalse(EditorContentIntegrity.containsAnyFile(pkg));

        // A second building nobody accepted.
        Files.copy(SHIPPED.resolve("silos.nbt"), pkg.resolve("buildings/shed.nbt"));
        assertTrue(EditorContentIntegrity.containsAnyFile(pkg));
        Files.delete(pkg.resolve("buildings/shed.nbt"));
        assertFalse(EditorContentIntegrity.containsAnyFile(pkg));

        // A sidecar with no building of its own is not covered by anybody's acceptance.
        Files.writeString(pkg.resolve("buildings/ghost.building.json"), "{\"weight\": 10}");
        assertTrue(EditorContentIntegrity.containsAnyFile(pkg));
        Files.delete(pkg.resolve("buildings/ghost.building.json"));

        // Any other kind of content beside it still counts.
        Files.createDirectories(pkg.resolve("templates"));
        Files.writeString(pkg.resolve("templates/cabin.nbt"), "x");
        assertTrue(EditorContentIntegrity.containsAnyFile(pkg));
    }

    @Test
    @DisplayName("changing an accepted building ends its exemption")
    void editedBuildingIsCustomAgain(@TempDir Path tmp) throws IOException {
        Path pkg = packageWith(tmp, "clock_tower", "water_tower");
        ApprovedBuildings.setAcceptedForTest(Set.of(hashOf(pkg, "clock_tower")));
        assertFalse(EditorContentIntegrity.containsAnyFile(pkg));

        // The same name, different blocks — what a save after an edit leaves behind.
        Path file = pkg.resolve("buildings/clock_tower.nbt");
        Files.delete(file);
        Files.copy(SHIPPED.resolve("silos.nbt"), file);
        assertTrue(EditorContentIntegrity.containsAnyFile(pkg));
    }

    @Test
    @DisplayName("a file that is not a readable building is never exempt")
    void unreadableFileIsNotExempt(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("user"));
        Files.createDirectories(pkg.resolve("buildings"));
        Files.writeString(pkg.resolve("buildings/junk.nbt"), "not nbt");
        ApprovedBuildings.setAcceptedForTest(Set.of("0".repeat(64)));
        assertTrue(EditorContentIntegrity.containsAnyFile(pkg));
    }
}
