package games.brennan.dungeontrain.perks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads {@code data/dungeontrain/perks/tree.json} (the editor's export rewritten to hand-written ids by
 * {@code scripts/perks/tree-from-editor.py}) into a {@link PerkTree}, once, on whichever side asks
 * first. Validation fails loud: a broken tree throws here, and {@link #get()} then serves an empty
 * tree so the menu opens on a dark sky instead of crashing.
 */
public final class PerkTreeLoader {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String RESOURCE = "/data/dungeontrain/perks/tree.json";
    private static final double CENTRE_TOLERANCE = 0.5;

    private static volatile PerkTree loaded;

    private PerkTreeLoader() {}

    /** The bundled tree. Never null; empty when the resource is missing or invalid. */
    public static PerkTree get() {
        PerkTree t = loaded;
        if (t == null) {
            synchronized (PerkTreeLoader.class) {
                t = loaded;
                if (t == null) {
                    t = loadBundled();
                    loaded = t;
                }
            }
        }
        return t;
    }

    private static PerkTree loadBundled() {
        try (InputStream in = PerkTreeLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.error("[DungeonTrain] perks tree missing: {}", RESOURCE);
                return empty();
            }
            PerkTree tree = parse(new StringReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            warnUnregistered(tree);
            LOGGER.info("[DungeonTrain] perks tree v{}: {} perks, {} links, {} classes",
                tree.version(), tree.perks().size(), tree.links().size(), tree.classes().size());
            return tree;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] perks tree invalid; the menu will be empty", e);
            return empty();
        }
    }

    static PerkTree empty() {
        return new PerkTree(0, List.of(),
            List.of(new PerkTree.Perk(PerkTree.ROOT, null, "The Engine", "", "", 1, PerkTree.CENTRE, PerkTree.CENTRE)),
            List.of());
    }

    /** Parse and validate; throws {@link IllegalArgumentException} on a bad tree. */
    public static PerkTree parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        int version = root.has("v") ? root.get("v").getAsInt() : 0;
        List<PerkTree.PerkClass> classes = new ArrayList<>();
        for (JsonElement e : array(root, "classes")) {
            JsonObject c = e.getAsJsonObject();
            classes.add(new PerkTree.PerkClass(str(c, "id"), str(c, "name"), parseColour(str(c, "color"))));
        }
        List<PerkTree.Perk> perks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement e : array(root, "perks")) {
            JsonObject p = e.getAsJsonObject();
            String id = str(p, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate perk id " + id);
            boolean isRoot = PerkTree.ROOT.equals(id);
            if (!isRoot && !id.matches("[a-z0-9]+(-[a-z0-9]+)*")) throw new IllegalArgumentException("perk id not kebab-case: " + id);
            int tier = isRoot ? 1 : p.get("tier").getAsInt();
            if (tier < PerkDef.MIN_TIER || tier > PerkDef.MAX_TIER) throw new IllegalArgumentException("tier out of range for " + id);
            String cls = p.has("cls") && !p.get("cls").isJsonNull() ? p.get("cls").getAsString() : null;
            if (!isRoot && (cls == null || classes.stream().noneMatch(c -> c.id().equals(cls)))) {
                throw new IllegalArgumentException("perk " + id + " has no known class");
            }
            perks.add(new PerkTree.Perk(id, cls, str(p, "name"), optStr(p, "desc"), optStr(p, "point"), tier,
                p.get("x").getAsDouble(), p.get("y").getAsDouble()));
        }
        List<PerkTree.Link> links = new ArrayList<>();
        for (JsonElement e : array(root, "links")) {
            JsonObject l = e.getAsJsonObject();
            String a = str(l, "a"), b = str(l, "b");
            if (!ids.contains(a) || !ids.contains(b)) throw new IllegalArgumentException("link to unknown perk " + a + " " + b);
            if (a.equals(b)) throw new IllegalArgumentException("link from " + a + " to itself");
            PerkTree.LinkKind kind = "cross".equals(optStr(l, "kind")) ? PerkTree.LinkKind.CROSS : PerkTree.LinkKind.REQ;
            links.add(new PerkTree.Link(a, b, kind, l.has("oneWay") && l.get("oneWay").getAsBoolean()));
        }
        PerkTree tree = new PerkTree(version, classes, perks, links);
        PerkTree.Perk rootPerk = tree.root();
        if (rootPerk == null) throw new IllegalArgumentException("tree has no root");
        if (Math.abs(rootPerk.x() - PerkTree.CENTRE) > CENTRE_TOLERANCE || Math.abs(rootPerk.y() - PerkTree.CENTRE) > CENTRE_TOLERANCE) {
            throw new IllegalArgumentException("root is not at the centre: " + rootPerk.x() + "," + rootPerk.y());
        }
        Set<String> joined = tree.connectedToRoot();
        for (PerkTree.Perk p : perks) {
            if (!joined.contains(p.id())) throw new IllegalArgumentException("perk " + p.id() + " floats free of the Engine");
        }
        return tree;
    }

    private static void warnUnregistered(PerkTree tree) {
        TierOnePerks.ensureRegistered(); // not bootstrap(): that reads this tree, which is still loading
        for (String id : PerkRegistry.ids()) {
            if (tree.perk(id).isEmpty()) LOGGER.warn("[DungeonTrain] registered perk {} is not in tree.json", id);
        }
    }

    private static JsonArray array(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) throw new IllegalArgumentException("missing array " + key);
        return o.getAsJsonArray(key);
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) throw new IllegalArgumentException("missing " + key);
        return o.get(key).getAsString();
    }

    private static String optStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** {@code #rrggbb} to opaque ARGB. */
    static int parseColour(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) throw new IllegalArgumentException("bad colour " + hex);
        return 0xFF000000 | Integer.parseInt(h, 16);
    }
}
