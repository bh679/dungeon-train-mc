package games.brennan.dungeontrain.building;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dev mode only: records that a shipped building's {@code .nbt} was saved from the editor, so the generator
 * stops rebuilding it from its recipe.
 *
 * <p>{@code scripts/lost-city/build-templates.py} writes every shipped building from an archetype recipe, and
 * its {@code --check} fails CI on any byte of drift — which a hand edit is. The README's hand-edit route is to
 * take the archetype out of the generator's output; {@code scripts/lost-city/authored.json} does that per
 * name, and the script takes the manifest's size from the saved template instead of the recipe.</p>
 */
public final class BuildingSourceAuthoring {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String AUTHORED_REL_PATH = "scripts/lost-city/authored.json";

    private BuildingSourceAuthoring() {}

    /** Add {@code name} to the checkout's authored list. A no-op outside a checkout. */
    public static void markAuthored(String name) throws IOException {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return;
        Path file = projectRoot.resolve(AUTHORED_REL_PATH);
        if (!Files.isDirectory(file.getParent())) return;
        Set<String> names = read(file);
        if (!names.add(name)) return;
        JsonArray out = new JsonArray();
        names.forEach(out::add);
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out) + "\n", StandardCharsets.UTF_8);
        LOGGER.info("[DungeonTrain] Marked building {} as hand-authored in {}", name, file);
    }

    private static Set<String> read(Path file) throws IOException {
        Set<String> names = new TreeSet<>();
        if (!Files.isRegularFile(file)) return names;
        for (JsonElement e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
            names.add(e.getAsString());
        }
        return names;
    }
}
