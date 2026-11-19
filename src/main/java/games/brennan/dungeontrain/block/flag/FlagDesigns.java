package games.brennan.dungeontrain.block.flag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.DyeColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Dungeon Train flag designs a {@link FlagPlaceholders flag placeholder} can become: a vanilla
 * banner base colour plus vanilla pattern layers. Read once from
 * {@code data/dungeontrain/flag_designs.json} on the classpath, so the list ships with the jar and
 * reads the same on every side.
 */
public final class FlagDesigns {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Classpath location of the design list. */
    public static final String RESOURCE = "/data/dungeontrain/flag_designs.json";

    /** One pattern layer: a vanilla banner pattern id (no namespace) and its dye. */
    public record Layer(String pattern, DyeColor color) {}

    /** One design: its sheet number, base banner colour and layers in paint order. */
    public record Design(String id, DyeColor base, List<Layer> layers) {
        public Design {
            layers = List.copyOf(layers);
        }
    }

    private static volatile List<Design> cached;

    private FlagDesigns() {}

    /** Every design, in file order. Never empty: a missing or broken file throws on first use. */
    public static List<Design> all() {
        List<Design> c = cached;
        if (c == null) {
            synchronized (FlagDesigns.class) {
                c = cached;
                if (c == null) {
                    c = load();
                    cached = c;
                }
            }
        }
        return c;
    }

    private static List<Design> load() {
        try (InputStream in = FlagDesigns.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing flag design list " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                List<Design> designs = parse(JsonParser.parseReader(reader));
                LOGGER.info("[DungeonTrain] Flags: loaded {} design(s).", designs.size());
                return designs;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read flag design list " + RESOURCE, e);
        }
    }

    /**
     * Parse the design list. A design with an unknown base colour is dropped and a layer with an
     * unknown colour is skipped, each with a warning, so one typo never takes every flag down.
     */
    static List<Design> parse(JsonElement root) {
        JsonArray array = root.getAsJsonObject().getAsJsonArray("designs");
        List<Design> out = new ArrayList<>(array.size());
        for (JsonElement el : array) {
            JsonObject obj = el.getAsJsonObject();
            String id = obj.get("id").getAsString();
            DyeColor base = DyeColor.byName(obj.get("base").getAsString(), null);
            if (base == null) {
                LOGGER.warn("[DungeonTrain] Flags: design {} has unknown base colour '{}'; skipped.",
                    id, obj.get("base").getAsString());
                continue;
            }
            List<Layer> layers = new ArrayList<>();
            for (JsonElement le : obj.getAsJsonArray("layers")) {
                JsonObject layer = le.getAsJsonObject();
                String pattern = layer.get("pattern").getAsString();
                DyeColor color = DyeColor.byName(layer.get("color").getAsString(), null);
                if (color == null) {
                    LOGGER.warn("[DungeonTrain] Flags: design {} layer '{}' has unknown colour '{}'; skipped.",
                        id, pattern, layer.get("color").getAsString());
                    continue;
                }
                layers.add(new Layer(pattern, color));
            }
            out.add(new Design(id, base, layers));
        }
        if (out.isEmpty()) throw new IllegalStateException("Flag design list " + RESOURCE + " is empty");
        return List.copyOf(out);
    }
}
