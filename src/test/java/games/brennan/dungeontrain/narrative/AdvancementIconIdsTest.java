package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Guards that every advancement's {@code display.icon.id} is a real item.
 *
 * <p>Written after the advancement editor saved {@code minecraft:clock_57} into Prime Time: the
 * clock's animation frames are item models and textures, but not registered items, so the icon
 * fails to parse and the whole advancement silently does not load.</p>
 *
 * <p>The unit-test launcher boots the full mod set, so the real item registry holds DT's items and
 * every sibling and companion mod's (Exposure, Vista, Edible Backpacks…) as well as vanilla's.</p>
 */
class AdvancementIconIdsTest {

    private static final Path ADVANCEMENTS = RepoPaths.advancements();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyAdvancementIconIsARealItem() throws IOException {
        assertTrue(Files.isDirectory(ADVANCEMENTS), "missing " + ADVANCEMENTS);
        List<String> bad = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> tree = Files.walk(ADVANCEMENTS)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String id = iconId(file);
                if (id == null) continue;
                checked++;
                String problem = problem(id);
                if (problem != null) bad.add(ADVANCEMENTS.relativize(file) + " → " + id + " (" + problem + ")");
            }
        }
        assertTrue(checked > 0, "found no advancement icons at all — is the path right?");
        assertTrue(bad.isEmpty(),
                "advancement icon(s) that are not items (the advancement would fail to load): " + bad);
    }

    /** {@code display.icon.id}, or null for an advancement with no display. */
    private static String iconId(Path file) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!root.has("display")) return null;
        JsonElement icon = root.getAsJsonObject("display").get("icon");
        if (icon == null || !icon.isJsonObject() || !icon.getAsJsonObject().has("id")) return "<no icon id>";
        return icon.getAsJsonObject().get("id").getAsString();
    }

    /** Why {@code id} is not an item, or null when it is. */
    private static String problem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return "not a resource location";
        return BuiltInRegistries.ITEM.containsKey(rl) ? null : "not a registered item";
    }
}
