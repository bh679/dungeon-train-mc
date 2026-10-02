package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.worldgen.LostCityTemplateIds.PoolView;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LostCityTemplateIdsTest {

    private static ResourceLocation rl(String id) {
        return ResourceLocation.parse(id);
    }

    private static ResourceLocation blc(String path) {
        return ResourceLocation.fromNamespaceAndPath(LostCityStructures.NAMESPACE, path);
    }

    /** The Big Lost City jar's {@code data/big_lost_city} as seen by the test classpath (the pinned version). */
    private static <T> T withBlcData(IoFunction<Path, T> body) throws Exception {
        URL marker = LostCityTemplateIdsTest.class.getClassLoader().getResource("data/big_lost_city/worldgen/template_pool");
        assertNotNull(marker, "Big Lost City is not on the test classpath");
        URI uri = marker.toURI();
        if (!"jar".equals(uri.getScheme())) return body.apply(Path.of(uri).getParent().getParent());
        try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
            return body.apply(fs.getPath("/data/big_lost_city"));
        }
    }

    @FunctionalInterface
    private interface IoFunction<A, B> {
        B apply(A a) throws IOException;
    }

    private static List<Path> jsons(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static JsonObject read(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    /** {@code namespace:path} for a JSON file under {@code root} (extension dropped). */
    private static ResourceLocation idOf(String namespace, Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return ResourceLocation.fromNamespaceAndPath(namespace, rel.substring(0, rel.length() - ".json".length()));
    }

    private static void addTemplates(JsonObject element, List<ResourceLocation> out) {
        if (element.has("location")) out.add(rl(element.get("location").getAsString()));
        if (element.has("elements")) {
            for (JsonElement part : element.getAsJsonArray("elements")) addTemplates(part.getAsJsonObject(), out);
        }
    }

    private static void readPools(String namespace, Path poolRoot, Map<ResourceLocation, PoolView> into) throws IOException {
        for (Path p : jsons(poolRoot)) {
            JsonObject pool = read(p);
            List<ResourceLocation> templates = new ArrayList<>();
            for (JsonElement e : pool.getAsJsonArray("elements")) addTemplates(e.getAsJsonObject().getAsJsonObject("element"), templates);
            String fallback = pool.has("fallback") ? pool.get("fallback").getAsString() : "minecraft:empty";
            into.put(idOf(namespace, poolRoot, p), new PoolView(templates, "minecraft:empty".equals(fallback) ? null : rl(fallback)));
        }
    }

    private static void readStarts(String namespace, Path structureRoot, List<ResourceLocation> into) throws IOException {
        for (Path p : jsons(structureRoot)) {
            JsonObject s = read(p);
            if (!LostCityTemplateIds.startable(idOf(namespace, structureRoot, p))) continue;
            if (s.has("start_pool")) into.add(rl(s.get("start_pool").getAsString()));
        }
    }

    private record Shipped(Set<ResourceLocation> collected, Set<ResourceLocation> templateFiles,
                           Set<ResourceLocation> referenced, List<ResourceLocation> dtStarts) {}

    private static Shipped shipped() throws Exception {
        return withBlcData(root -> {
            Map<ResourceLocation, PoolView> pools = new HashMap<>();
            readPools(LostCityStructures.NAMESPACE, root.resolve("worldgen/template_pool"), pools);
            Path dt = RepoPaths.resources().resolve("data/dungeontrain");
            readPools("dungeontrain", dt.resolve("worldgen/template_pool"), pools);

            List<ResourceLocation> starts = new ArrayList<>();
            readStarts(LostCityStructures.NAMESPACE, root.resolve("worldgen/structure"), starts);
            List<ResourceLocation> dtStarts = new ArrayList<>();
            readStarts("dungeontrain", dt.resolve("worldgen/structure"), dtStarts);
            starts.addAll(dtStarts);

            Set<ResourceLocation> files = new TreeSet<>();
            Path structures = root.resolve("structure");
            try (Stream<Path> nbts = Files.walk(structures)) {
                nbts.filter(p -> p.toString().endsWith(".nbt")).forEach(p -> {
                    String rel = structures.relativize(p).toString().replace('\\', '/');
                    files.add(blc(rel.substring(0, rel.length() - ".nbt".length())));
                });
            }
            Set<ResourceLocation> referenced = new TreeSet<>();
            for (Map.Entry<ResourceLocation, PoolView> e : pools.entrySet()) {
                if (!LostCityStructures.NAMESPACE.equals(e.getKey().getNamespace())) continue;
                e.getValue().templates().forEach(referenced::add);
            }
            return new Shipped(LostCityTemplateIds.collect(starts, pools::get), files, referenced, dtStarts);
        });
    }

    @Test
    @DisplayName("the shipped structures reach exactly the 42 templates Big Lost City's pools name, none of the other 33")
    void shippedDataReachesOnlyReferencedTemplates() throws Exception {
        Shipped s = shipped();
        assertEquals(75, s.templateFiles().size(), "Big Lost City's template count changed — re-check the pre-load");
        assertEquals(42, s.collected().size(), s.collected().toString());
        assertEquals(s.referenced(), s.collected());
        assertTrue(s.templateFiles().containsAll(s.collected()), "a collected template has no .nbt");
        for (String never : List.of("warship", "warshiplt", "parking_garage", "parking_garagelt", "tent3",
                "ruined_skyscraper", "tall_skyscraper", "house2")) {
            assertFalse(s.collected().contains(blc(never)), never);
        }
        Set<ResourceLocation> unreferenced = new TreeSet<>(s.templateFiles());
        unreferenced.removeAll(s.collected());
        assertEquals(33, unreferenced.size(), unreferenced.toString());
    }

    @Test
    @DisplayName("DT's trackside copies start from Big Lost City pools")
    void tracksideStartsAreBlcPools() throws Exception {
        Shipped s = shipped();
        long blcStarts = s.dtStarts().stream().filter(id -> LostCityStructures.NAMESPACE.equals(id.getNamespace())).count();
        assertTrue(blcStarts > 0);
    }

    @Test
    @DisplayName("Terrain Fit's all-biome copies never count as a start")
    void terrainFitCopiesAreNotStartable() {
        assertFalse(LostCityTemplateIds.startable(rl("lostcityterrainfit:all_biome/warehouse")));
        assertTrue(LostCityTemplateIds.startable(blc("warehouse")));
        assertTrue(LostCityTemplateIds.startable(rl("dungeontrain:lost_city/warehouse")));
        assertFalse(LostCityTemplateIds.startable(rl("minecraft:village_plains")));
    }

    @Test
    @DisplayName("a pool's fallback chain is followed, cycles end, and other namespaces are dropped")
    void fallbackChain() {
        Map<ResourceLocation, PoolView> pools = Map.of(
                rl("t:a"), new PoolView(List.of(blc("a"), rl("dungeontrain:lost_city/own")), rl("t:b")),
                rl("t:b"), new PoolView(List.of(), rl("t:c")),
                rl("t:c"), new PoolView(List.of(blc("c"), blc("c")), rl("t:a")));
        assertEquals(Set.of(blc("a"), blc("c")), LostCityTemplateIds.collect(List.of(rl("t:a")), pools::get));
    }

    @Test
    @DisplayName("an unknown start pool contributes nothing")
    void unknownPool() {
        assertTrue(LostCityTemplateIds.collect(List.of(rl("t:missing")), id -> null).isEmpty());
    }
}
