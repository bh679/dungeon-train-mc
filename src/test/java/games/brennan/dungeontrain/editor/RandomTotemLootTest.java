package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DT's loot hands out Balanced Totems ({@code dungeontrain:random_totem}) wherever it used to hand out
 * the vanilla Totem of Undying, except ominous vaults, which keep the vanilla one. The ominous pools
 * baked into templates ({@code contents/*.variants.json} and {@code .nbt}) are left alone, so only the
 * hand-editable loot lists are pinned here.
 */
final class RandomTotemLootTest {

    private static final Path DATA = RepoPaths.root().resolve("src/main/resources/data/dungeontrain");
    private static final String VANILLA = "\"minecraft:totem_of_undying\"";
    private static final String RANDOM = "\"dungeontrain:random_totem\"";

    @Test
    @DisplayName("container loot lists carry the random totem, never the vanilla one")
    void containersUseRandomTotem() throws IOException {
        int randoms = 0;
        try (Stream<Path> files = Files.list(DATA.resolve("containers"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String body = Files.readString(f, StandardCharsets.UTF_8);
                assertTrue(!body.contains(VANILLA), f.getFileName() + " still lists a vanilla Totem of Undying");
                if (body.contains(RANDOM)) randoms++;
            }
        }
        assertTrue(randoms > 50, "expected the random totem across the container lists, found " + randoms);
    }

    @Test
    @DisplayName("prefabs: green vault, vase and pot roll random totems; the ominous vault keeps vanilla")
    void prefabsSplitOnOminous() throws IOException {
        for (String name : List.of("green_trial_vault", "ancient_vase", "pot")) {
            String body = read("prefabs/loot/" + name + ".json");
            assertTrue(body.contains(RANDOM) && !body.contains(VANILLA), name + " should roll the random totem");
        }
        String ominous = read("prefabs/loot/ominous_trial_vault.json");
        assertTrue(ominous.contains(VANILLA) && !ominous.contains(RANDOM), "ominous vaults keep the vanilla totem");
    }

    private static String read(String rel) throws IOException {
        return Files.readString(DATA.resolve(rel), StandardCharsets.UTF_8);
    }
}
