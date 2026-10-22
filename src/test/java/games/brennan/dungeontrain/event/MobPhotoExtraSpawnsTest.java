package games.brennan.dungeontrain.event;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The photo collections' generator ({@code scripts/advancements/generate-mob-photo-collections.py})
 * reads spawns decided in Java from {@code extra_spawns} in its category table. These pin that list
 * to the spawners, so a mob added in code reaches Most Wanted / Nature Documentary too.
 */
class MobPhotoExtraSpawnsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Set<String> extraSpawns(String source) throws IOException {
        JsonObject doc = JsonParser.parseString(Files.readString(
                RepoPaths.root().resolve("scripts/advancements/mob-photos/categories.json"))).getAsJsonObject();
        Set<String> out = new HashSet<>();
        doc.getAsJsonObject("extra_spawns").getAsJsonArray(source).forEach(e -> out.add(e.getAsString()));
        return out;
    }

    @Test
    @DisplayName("the Nether band's rosters are all listed")
    void netherRosters() throws IOException {
        Set<String> roster = NetherMobSpawner.rosterTypes().stream()
                .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())
                .collect(Collectors.toSet());
        assertEquals(roster, extraSpawns("event/NetherMobSpawner"));
    }

    @Test
    @DisplayName("Deeper Darker's pot mobs are all listed")
    void potMobs() throws IOException {
        Set<String> pots = GloomslatePotMobEvents.POT_MOBS.stream()
                .map(ResourceLocation::toString)
                .collect(Collectors.toSet());
        assertEquals(pots, extraSpawns("event/GloomslatePotMobEvents"));
    }
}
