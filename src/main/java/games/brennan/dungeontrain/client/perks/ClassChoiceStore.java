package games.brennan.dungeontrain.client.perks;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The classes each player has hidden in the perks menu, kept on this computer like the web page keeps
 * them per viewer: {@code config/dungeontrain/perks-classes.json}, player UUID to hidden class ids.
 * A player with no entry has never chosen: the menu starts them with every class hidden, so the first
 * time anyone sees the menu (on death included) no class is selected. A player with no perk unlocked
 * yet starts with every class hidden even when this computer saved a choice (another world, say).
 */
@OnlyIn(Dist.CLIENT)
final class ClassChoiceStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private ClassChoiceStore() {}

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("dungeontrain").resolve("perks-classes.json");
    }

    private static JsonObject readAll() {
        Path f = file();
        if (!Files.exists(f)) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("[DungeonTrain] perks class choice unreadable, starting fresh: {}", ex.toString());
            return new JsonObject();
        }
    }

    /** The hidden classes this player chose, or empty when they never have. */
    static Optional<Set<String>> load(UUID player) {
        JsonObject all = readAll();
        JsonElement e = all.get(player.toString());
        if (e == null || !e.isJsonArray()) return Optional.empty();
        Set<String> out = new LinkedHashSet<>();
        e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
        return Optional.of(Set.copyOf(out));
    }

    static void save(UUID player, Set<String> hidden) {
        JsonObject all = readAll();
        JsonArray arr = new JsonArray();
        hidden.stream().sorted().forEach(arr::add);
        all.add(player.toString(), arr);
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("[DungeonTrain] could not save the perks class choice: {}", ex.toString());
        }
    }
}
