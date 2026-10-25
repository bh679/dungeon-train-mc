package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cosmic Debris is handed out per carriage group ({@link CosmicDebrisDrops}), not by loot pools: a pool
 * entry would stack a second, uncounted source on top of the one-in-five draw.
 */
final class CosmicDebrisLootTest {

    @Test
    @DisplayName("no loot prefab carries Cosmic Debris")
    void noPrefabEntry() throws IOException {
        Path dir = RepoPaths.resources().resolve("data/dungeontrain/prefabs/loot");
        try (Stream<Path> files = Files.list(dir)) {
            List<String> offenders = files.filter(p -> {
                try {
                    return Files.readString(p).contains("dungeontrain:cosmic_debris");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).map(p -> p.getFileName().toString()).toList();
            assertTrue(offenders.isEmpty(), "prefabs with debris: " + offenders);
        }
    }
}
