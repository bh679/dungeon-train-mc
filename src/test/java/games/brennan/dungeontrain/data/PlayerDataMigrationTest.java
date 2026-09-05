package games.brennan.dungeontrain.data;

import games.brennan.dungeontrain.data.PlayerDataPaths.Kind;
import games.brennan.dungeontrain.data.PlayerDataPaths.Relocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration that moves player data out of {@code config/}.
 *
 * <p>What these assert is the promise the class makes: it can move everything across, and it
 * cannot lose anything on the way. {@code migrate(Path, Path)} takes both roots explicitly for
 * exactly this reason — anything reaching {@code FMLPaths} can't be bootstrapped in a unit test.</p>
 */
class PlayerDataMigrationTest {

    @TempDir
    Path tmp;

    private Path configDir() throws IOException {
        return Files.createDirectories(tmp.resolve("config"));
    }

    private Path dataRoot() {
        return tmp.resolve("dungeontrain");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void movesEveryRelocatedEntry() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        for (Relocation relocation : PlayerDataPaths.RELOCATIONS) {
            Path legacy = relocation.legacyPath(config);
            write(relocation.kind() == Kind.FILE ? legacy : legacy.resolve("a.dat"),
                relocation.newRelative());
        }

        PlayerDataMigration.Result result = PlayerDataMigration.migrate(config, data);

        assertEquals(PlayerDataPaths.RELOCATIONS.size(), result.movedFiles());
        assertTrue(result.clean());
        for (Relocation relocation : PlayerDataPaths.RELOCATIONS) {
            Path moved = relocation.kind() == Kind.FILE
                ? relocation.newPath(data)
                : relocation.newPath(data).resolve("a.dat");
            assertTrue(Files.isRegularFile(moved), "not migrated: " + relocation.newRelative());
            assertEquals(relocation.newRelative(), Files.readString(moved));
            assertFalse(Files.exists(relocation.legacyPath(config)),
                "left behind in config/: " + relocation.legacyRelative());
        }
    }

    @Test
    void nestedBuildDirectoriesKeepTheirLayout() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        write(config.resolve("dungeontrain/user/parts/cab/front.nbt"), "cab");
        write(config.resolve("dungeontrain/user/pillars/top/a.nbt"), "pillar");

        PlayerDataMigration.migrate(config, data);

        assertEquals("cab", Files.readString(data.resolve("user/parts/cab/front.nbt")));
        assertEquals("pillar", Files.readString(data.resolve("user/pillars/top/a.nbt")));
    }

    @Test
    void isIdempotent() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        write(config.resolve("dungeontrain-achievements/uuid.json"), "granted");

        assertEquals(1, PlayerDataMigration.migrate(config, data).movedFiles());
        PlayerDataMigration.Result second = PlayerDataMigration.migrate(config, data);

        assertEquals(0, second.movedFiles());
        assertEquals(0, second.skippedExisting());
        assertEquals("granted", Files.readString(data.resolve("achievements/uuid.json")));
    }

    @Test
    void neverOverwritesContentAlreadyAtTheDestination() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        write(config.resolve("dungeontrain-stats/uuid.json"), "old");
        write(data.resolve("stats/uuid.json"), "new");

        PlayerDataMigration.Result result = PlayerDataMigration.migrate(config, data);

        assertEquals(0, result.movedFiles());
        assertEquals(1, result.skippedExisting());
        assertEquals("new", Files.readString(data.resolve("stats/uuid.json")),
            "the destination file must win");
        assertEquals("old", Files.readString(config.resolve("dungeontrain-stats/uuid.json")),
            "the source must be left in place, never deleted");
    }

    @Test
    void leavesTheIntegrityGovernedConfigsAlone() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        // These are held to their shipped defaults by AisDataIntegrity / DtConfigIntegrity, which
        // read them straight out of config/. Moving one would break the Free Play check.
        write(config.resolve("adventureitemstats.properties"), "x");
        write(config.resolve("dungeontrain-server.toml"), "x");
        write(config.resolve("dungeontrain/cheat-mods.json"), "x");

        PlayerDataMigration.migrate(config, data);

        assertTrue(Files.isRegularFile(config.resolve("adventureitemstats.properties")));
        assertTrue(Files.isRegularFile(config.resolve("dungeontrain-server.toml")));
        assertTrue(Files.isRegularFile(config.resolve("dungeontrain/cheat-mods.json")));
    }

    @Test
    void prunesEmptiedDirectoriesButKeepsOnesStillHoldingFiles() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        write(config.resolve("dungeontrain-narrative/global.json"), "n");
        write(config.resolve("dungeontrain/user/templates/a.nbt"), "t");
        // A stray file the migration doesn't know about must keep its directory alive.
        write(config.resolve("dungeontrain/cheat-mods.json"), "x");

        PlayerDataMigration.migrate(config, data);

        assertFalse(Files.exists(config.resolve("dungeontrain-narrative")));
        assertFalse(Files.exists(config.resolve("dungeontrain/user")));
        assertTrue(Files.isDirectory(config.resolve("dungeontrain")));
    }

    @Test
    void hasLegacyDataSeesFilesAndNotEmptyFolders() throws IOException {
        Path config = configDir();
        assertFalse(PlayerDataMigration.hasLegacyData(config));

        Files.createDirectories(config.resolve("dungeontrain/user/templates"));
        assertFalse(PlayerDataMigration.hasLegacyData(config),
            "an empty folder is what a fresh install looks like");

        write(config.resolve("dungeontrain/user/templates/a.nbt"), "t");
        assertTrue(PlayerDataMigration.hasLegacyData(config));
    }
}
