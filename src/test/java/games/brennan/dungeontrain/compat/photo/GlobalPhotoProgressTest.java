package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GlobalPhotoProgress}: the photo tier tallies and biome album, carried across worlds. */
class GlobalPhotoProgressTest {

    private static final UUID PLAYER = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        GlobalPhotoProgress.resetForTests();
    }

    @Test
    @DisplayName("merging keeps the larger count and every biome, so it never loses a photo")
    void mergeOnlyGrows() {
        GlobalPhotoProgress.Tally a = new GlobalPhotoProgress.Tally(
            Map.of("hostile", 40, "animal", 3), Set.of("minecraft:plains"));
        GlobalPhotoProgress.Tally b = new GlobalPhotoProgress.Tally(
            Map.of("hostile", 12, "passenger", 5), Set.of("minecraft:desert"));

        GlobalPhotoProgress.Tally merged = a.merge(b);

        assertEquals(Map.of("hostile", 40, "animal", 3, "passenger", 5), merged.counts());
        assertEquals(Set.of("minecraft:plains", "minecraft:desert"), merged.biomes());
        assertEquals(merged, b.merge(a));
    }

    @Test
    @DisplayName("a new world reads back what the last one banked")
    void survivesRestart() {
        GlobalPhotoProgress.useDirectoryForTests(dir);
        GlobalPhotoProgress.merge(PLAYER, new GlobalPhotoProgress.Tally(Map.of("hostile", 7), Set.of("minecraft:plains")));
        GlobalPhotoProgress.flushAndClearAll();

        GlobalPhotoProgress.Tally read = GlobalPhotoProgress.read(PLAYER);
        assertEquals(Map.of("hostile", 7), read.counts());
        assertEquals(Set.of("minecraft:plains"), read.biomes());
    }

    @Test
    @DisplayName("an older world's smaller tally can't shrink the banked one")
    void olderWorldDoesNotShrink() {
        GlobalPhotoProgress.useDirectoryForTests(dir);
        GlobalPhotoProgress.merge(PLAYER, new GlobalPhotoProgress.Tally(Map.of("hostile", 30), Set.of()));
        GlobalPhotoProgress.Tally merged = GlobalPhotoProgress.merge(PLAYER,
            new GlobalPhotoProgress.Tally(Map.of("hostile", 2), Set.of()));
        assertEquals(30, merged.counts().get("hostile"));
    }

    @Test
    @DisplayName("the stats reset deletes the file")
    void deleteFor() throws Exception {
        GlobalPhotoProgress.useDirectoryForTests(dir);
        GlobalPhotoProgress.merge(PLAYER, new GlobalPhotoProgress.Tally(Map.of("animal", 1), Set.of()));
        GlobalPhotoProgress.flush();
        assertTrue(GlobalPhotoProgress.hasAny(PLAYER));
        assertTrue(GlobalPhotoProgress.deleteFor(PLAYER));
        assertFalse(GlobalPhotoProgress.hasAny(PLAYER));
    }
}
