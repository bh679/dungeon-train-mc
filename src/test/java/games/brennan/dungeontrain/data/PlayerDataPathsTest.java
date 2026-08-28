package games.brennan.dungeontrain.data;

import games.brennan.dungeontrain.data.PlayerDataPaths.Kind;
import games.brennan.dungeontrain.data.PlayerDataPaths.Relocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the relocation table itself.
 *
 * <p>Reflective, for the reason {@code UserContentMigrationTest} is: the live path accessors go
 * through {@code FMLPaths}, which a unit test can't bootstrap. What can be checked without it is
 * the thing that actually goes wrong — a store whose constant drifts away from the table, so its
 * files quietly stay in {@code config/} where the next modpack update deletes them.</p>
 */
class PlayerDataPathsTest {

    /** Read a private {@code static final String} constant off a store class. */
    private static String constant(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static Relocation byNewRelative(String newRelative) {
        return PlayerDataPaths.RELOCATIONS.stream()
            .filter(r -> r.newRelative().equals(newRelative))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no relocation for " + newRelative));
    }

    @Test
    void everyStoresLegacyConstantMatchesTheRelocationTable() throws Exception {
        assertEquals(
            constant(games.brennan.dungeontrain.advancement.GlobalAchievementStore.class,
                "LEGACY_DIR_NAME"),
            byNewRelative(PlayerDataPaths.ACHIEVEMENTS).legacyRelative());
        assertEquals(
            constant(games.brennan.dungeontrain.advancement.GlobalPlayerStats.class,
                "LEGACY_DIR_NAME"),
            byNewRelative(PlayerDataPaths.STATS).legacyRelative());
        assertEquals(
            constant(games.brennan.dungeontrain.advancement.GlobalBookBurnStats.class,
                "LEGACY_DIR_NAME"),
            byNewRelative(PlayerDataPaths.STATS).legacyRelative(),
            "book burns share the stats directory");
        assertEquals(
            constant(games.brennan.dungeontrain.advancement.GlobalNarrativeProgress.class,
                "LEGACY_DIR_NAME"),
            byNewRelative(PlayerDataPaths.NARRATIVE).legacyRelative());
    }

    @Test
    void everyOutboxIsRelocatedUnderItsOwnLegacyName() throws Exception {
        Set<String> legacyNames = new HashSet<>();
        for (Relocation relocation : PlayerDataPaths.RELOCATIONS) {
            if (relocation.newRelative().startsWith(PlayerDataPaths.OUTBOX + "/")) {
                legacyNames.add(relocation.legacyRelative());
            }
        }
        assertTrue(legacyNames.contains(constant(
            games.brennan.dungeontrain.net.relay.RelayOutbox.class, "FILE_NAME")));
        assertTrue(legacyNames.contains(constant(
            games.brennan.dungeontrain.client.chat.ChatOutbox.class, "FILE_NAME")));
        assertTrue(legacyNames.contains(constant(
            games.brennan.dungeontrain.client.localization.edit.TranslationOutbox.class, "FILE_NAME")));
    }

    @Test
    void noEntryIsListedTwice() {
        Set<String> legacy = new HashSet<>();
        Set<String> current = new HashSet<>();
        for (Relocation relocation : PlayerDataPaths.RELOCATIONS) {
            assertTrue(legacy.add(relocation.legacyRelative()),
                "duplicate legacy path: " + relocation.legacyRelative());
            assertTrue(current.add(relocation.newRelative()),
                "duplicate destination: " + relocation.newRelative());
        }
    }

    @Test
    void nothingIntegrityGovernedIsRelocated() {
        // AisDataIntegrity and DtConfigIntegrity read these straight out of config/ and hold them
        // to their shipped defaults. Moving one would break the Free Play check that reads it.
        Set<String> governed = Set.of("adventureitemstats.properties", "dungeontrain-server.toml",
            "dungeontrain-common.toml", "dungeontrain/cheat-mods.json");
        for (Relocation relocation : PlayerDataPaths.RELOCATIONS) {
            assertFalse(governed.contains(relocation.legacyRelative()),
                "must stay in config/: " + relocation.legacyRelative());
        }
    }

    @Test
    void relativePathsResolveUnderTheirRoot() {
        Path config = Path.of("/instance/config");
        Path data = Path.of("/instance/dungeontrain");
        Relocation user = byNewRelative(PlayerDataPaths.USER);

        assertEquals(Kind.DIRECTORY, user.kind());
        assertEquals(config.resolve("dungeontrain").resolve("user"), user.legacyPath(config));
        assertEquals(data.resolve("user"), user.newPath(data));

        Relocation state = byNewRelative(PlayerDataPaths.DTPACKS_STATE);
        assertEquals(Kind.FILE, state.kind());
        assertEquals(data.resolve(PlayerDataPaths.DTPACKS_STATE), state.newPath(data));
    }
}
