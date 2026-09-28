package games.brennan.dungeontrain.client;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The on-disk half of {@link TrackedAdvancements}: a flat id array that round-trips and tolerates junk. */
class TrackedAdvancementsStoreTest {

    private static final ResourceLocation PACIFIST = ResourceLocation.parse("dungeontrain:dungeon_train/pacifist_100");
    private static final ResourceLocation FAR_START = ResourceLocation.parse("dungeontrain:dungeon_train/the_far_start");

    @Test
    void roundTripsIdsInOrder(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve(TrackedAdvancements.FILE_NAME);
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        ids.add(PACIFIST);
        ids.add(FAR_START);

        TrackedAdvancements.save(file, ids);

        assertTrue(Files.isRegularFile(file), "save creates parent directories");
        assertEquals(ids, TrackedAdvancements.read(file));
    }

    @Test
    void missingFileReadsAsEmpty(@TempDir Path dir) {
        assertEquals(Set.of(), TrackedAdvancements.read(dir.resolve("absent.json")));
    }

    @Test
    void malformedEntriesAreSkipped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TrackedAdvancements.FILE_NAME);
        Files.writeString(file, "[\"dungeontrain:dungeon_train/pacifist_100\", 42, \"NOT AN ID!\", {\"x\":1}]");

        assertEquals(Set.of(PACIFIST), TrackedAdvancements.read(file));
    }

    @Test
    void nonArrayFileReadsAsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TrackedAdvancements.FILE_NAME);
        Files.writeString(file, "{\"tracked\": []}");

        assertEquals(Set.of(), TrackedAdvancements.read(file));
    }
}
