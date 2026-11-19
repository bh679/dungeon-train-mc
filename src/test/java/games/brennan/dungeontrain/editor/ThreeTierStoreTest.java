package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the generic store's tier order, cache discipline and the two policies the review asked every
 * store to decide: where a save/delete mirrors to, and what a delete leaves in the cache.
 *
 * <p>Runs against temp directories for the user tier and the source tree, a JSON codec over a tiny
 * record, and a bundled copy on the test classpath ({@code /threetier/bundled/shipped.json}). No
 * Forge bootstrap: the FMLPaths-backed defaults are swapped out through the descriptor's seams.</p>
 */
final class ThreeTierStoreTest {

    /** The value under test. */
    record Doc(String text) {
        static Doc fromJson(JsonObject o) { return new Doc(o.get("text").getAsString()); }

        JsonElement toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("text", text);
            return o;
        }
    }

    private static final String SUBDIR = "threetier";
    private static final String BUNDLED_PREFIX = "/threetier/bundled/";
    private static final String SOURCE_REL = "src/main/resources/threetier";

    @TempDir Path active;
    @TempDir Path imported;
    @TempDir Path checkout;

    private final AtomicBoolean devMode = new AtomicBoolean(false);
    private final AtomicInteger decodes = new AtomicInteger();
    private final List<String> saved = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<String> renamed = new ArrayList<>();
    private final AtomicInteger cleared = new AtomicInteger();

    private final StoreDescriptor.UserTier userTier = new StoreDescriptor.UserTier() {
        @Override public Path writeDir(String subdir) { return active.resolve(subdir); }

        @Override public Path findFile(String subdir, String fileName) {
            for (Path d : List.of(active.resolve(subdir), imported.resolve(subdir))) {
                Path f = d.resolve(fileName);
                if (Files.isRegularFile(f)) return f;
            }
            return null;
        }
    };

    @BeforeEach
    void sourceTreeOnDisk() throws IOException {
        Files.createDirectories(checkout.resolve(SOURCE_REL));
    }

    private StoreDescriptor<String, Doc> describe(String name) {
        StoreCodec<Doc> json = StoreCodecs.json(Doc::fromJson, Doc::toJson);
        StoreCodec<Doc> counting = new StoreCodec<>() {
            @Override public Doc decode(InputStream in, String what, DecodeContext ctx) throws IOException {
                decodes.incrementAndGet();
                return json.decode(in, what, ctx);
            }
            @Override public void encode(Doc value, Path file) throws IOException { json.encode(value, file); }
        };
        return StoreDescriptor.<String, Doc>named(name)
            .id(id -> id)
            .subdir(SUBDIR)
            .fileName(id -> id + ".json")
            .bundledUnder(BUNDLED_PREFIX)
            .sourceRelDir(SOURCE_REL)
            .codec(counting)
            .logNoun("doc")
            .userTier(userTier)
            .sourceTree(() -> checkout)
            .devMode(devMode::get)
            .onSave(saved::add)
            .onDelete(deleted::add)
            .onRename((a, b) -> renamed.add(a + "->" + b))
            .onClear(cleared::incrementAndGet);
    }

    private ThreeTierStore<String, Doc> store() {
        return describe("test").build();
    }

    private void writeUser(Path root, String id, String text) throws IOException {
        Path f = root.resolve(SUBDIR).resolve(id + ".json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"text\":\"" + text + "\"}", StandardCharsets.UTF_8);
    }

    private Path sourceFile(String name) {
        return checkout.resolve(SOURCE_REL).resolve(name);
    }

    // ---- tiers ----------------------------------------------------------------------------------

    @Test
    @DisplayName("user tier wins over bundled; bundled is the fallback; absent is empty")
    void tierOrder() throws IOException {
        ThreeTierStore<String, Doc> s = store();
        assertEquals(Optional.of(new Doc("bundled")), s.get("shipped"), "no user copy: bundled");
        writeUser(active, "shipped", "user");
        s.invalidate("shipped");
        assertEquals(Optional.of(new Doc("user")), s.get("shipped"), "user copy shadows bundled");
        assertEquals(Optional.empty(), s.get("nowhere"));
    }

    @Test
    @DisplayName("an imported package's copy resolves on read but only the active package counts for existsInActive")
    void importedCopyResolves() throws IOException {
        writeUser(imported, "pkg", "imported");
        ThreeTierStore<String, Doc> s = store();
        assertEquals(Optional.of(new Doc("imported")), s.get("pkg"));
        assertTrue(s.existsInPackages("pkg"));
        assertFalse(s.existsInActive("pkg"));
        writeUser(active, "pkg", "mine");
        assertTrue(s.existsInActive("pkg"));
    }

    @Test
    @DisplayName("a bundled file that is not a JSON object reads as absent")
    void brokenBundledIsAbsent() {
        ThreeTierStore<String, Doc> s = store();
        assertEquals(Optional.empty(), s.get("broken"));
        assertEquals(Optional.empty(), s.get("array"));
        assertTrue(s.bundled("array"), "the resource is there; it is just unusable");
    }

    // ---- cache ----------------------------------------------------------------------------------

    @Test
    @DisplayName("get decodes once per key, including a cached absence, and is ungated")
    void cachesOnce() {
        ThreeTierStore<String, Doc> s = store();
        for (int i = 0; i < 5; i++) s.get("shipped");
        for (int i = 0; i < 5; i++) s.get("nowhere");
        assertEquals(1, decodes.get(), "one decode for the hit; the miss never reaches the codec");
        assertTrue(s.isCached("nowhere"), "absence is cached too");
        // A facade filter on the result must not touch the cache.
        Optional<Doc> gated = s.get("shipped").filter(d -> d.text().equals("something else"));
        assertTrue(gated.isEmpty());
        assertEquals(Optional.of(new Doc("bundled")), s.peekCached("shipped"), "the raw value stays cached");
    }

    @Test
    @DisplayName("invalidate re-reads; clearCache drops everything and fires onClear")
    void invalidateAndClear() {
        ThreeTierStore<String, Doc> s = store();
        s.get("shipped");
        s.invalidate("shipped");
        assertFalse(s.isCached("shipped"));
        s.get("shipped");
        assertEquals(2, decodes.get());
        s.clearCache();
        assertFalse(s.isCached("shipped"));
        assertEquals(1, cleared.get());
    }

    @Test
    @DisplayName("getBundled never consults the cache or the user tier")
    void getBundledIsRaw() throws IOException {
        writeUser(active, "shipped", "user");
        ThreeTierStore<String, Doc> s = store();
        assertEquals(Optional.of(new Doc("user")), s.get("shipped"));
        assertEquals(Optional.of(new Doc("bundled")), s.getBundled("shipped"));
    }

    // ---- save -----------------------------------------------------------------------------------

    @Test
    @DisplayName("save writes the active file, caches the value (PUT) and fires onSave")
    void savePut() throws IOException {
        ThreeTierStore<String, Doc> s = store();
        s.save("fresh", new Doc("v1"));
        assertTrue(Files.isRegularFile(active.resolve(SUBDIR).resolve("fresh.json")));
        assertEquals(Optional.of(new Doc("v1")), s.peekCached("fresh"));
        assertEquals(List.of("fresh"), saved);
        assertEquals(0, decodes.get(), "the cached value is the one just written, not a re-read");
    }

    @Test
    @DisplayName("SaveCachePolicy.DROP re-reads the file on the next get")
    void saveDrop() throws IOException {
        ThreeTierStore<String, Doc> s = describe("drop")
            .saveCachePolicy(StoreDescriptor.SaveCachePolicy.DROP).build();
        s.save("fresh", new Doc("v1"));
        assertFalse(s.isCached("fresh"));
        assertEquals(Optional.of(new Doc("v1")), s.get("fresh"));
        assertEquals(1, decodes.get());
    }

    @Test
    @DisplayName("rename moves the file and the cache entry and fires onRename")
    void rename() throws IOException {
        ThreeTierStore<String, Doc> s = store();
        s.save("a", new Doc("v1"));
        assertTrue(s.rename("a", "b"));
        assertFalse(s.existsInActive("a"));
        assertTrue(s.existsInActive("b"));
        assertEquals(Optional.of(new Doc("v1")), s.peekCached("b"));
        assertFalse(s.isCached("a"));
        assertEquals(List.of("a->b"), renamed);
        assertFalse(s.rename("missing", "c"), "nothing to move");
    }

    // ---- delete ---------------------------------------------------------------------------------

    @Test
    @DisplayName("REMOVE: deleting a user override of a shipped id resolves to the bundled copy at once")
    void deleteRemoveFallsBackToBundled() throws IOException {
        ThreeTierStore<String, Doc> s = store();
        s.save("shipped", new Doc("override"));
        assertEquals(Optional.of(new Doc("override")), s.get("shipped"));
        assertTrue(s.delete("shipped"));
        assertEquals(Optional.of(new Doc("bundled")), s.get("shipped"));
        assertEquals(List.of("shipped"), deleted);
    }

    @Test
    @DisplayName("TOMBSTONE: a deleted key reads as absent until invalidated")
    void deleteTombstone() throws IOException {
        ThreeTierStore<String, Doc> s = describe("tomb")
            .deleteCachePolicy(DeleteCachePolicy.TOMBSTONE).build();
        s.save("shipped", new Doc("override"));
        s.delete("shipped");
        assertEquals(Optional.empty(), s.get("shipped"));
        s.invalidate("shipped");
        assertEquals(Optional.of(new Doc("bundled")), s.get("shipped"));
    }

    @Test
    @DisplayName("delete of a key with no active file returns false and still clears the cache")
    void deleteMissing() throws IOException {
        ThreeTierStore<String, Doc> s = store();
        s.get("shipped");
        assertFalse(s.delete("shipped"));
        assertFalse(s.isCached("shipped"));
    }

    // ---- source policy --------------------------------------------------------------------------

    @Test
    @DisplayName("NEVER: save and delete leave the source tree alone")
    void policyNever() throws IOException {
        Files.writeString(sourceFile("x.json"), "{\"text\":\"old\"}");
        ThreeTierStore<String, Doc> s = describe("never").sourcePolicy(SourcePolicy.NEVER).build();
        s.save("x", new Doc("new"));
        assertEquals("{\"text\":\"old\"}", Files.readString(sourceFile("x.json")));
        s.delete("x");
        assertTrue(Files.exists(sourceFile("x.json")));
    }

    @Test
    @DisplayName("ALWAYS: save mirrors and delete removes the mirror whenever the tree is writable")
    void policyAlways() throws IOException {
        ThreeTierStore<String, Doc> s = describe("always").sourcePolicy(SourcePolicy.ALWAYS).build();
        s.save("x", new Doc("new"));
        assertTrue(Files.isRegularFile(sourceFile("x.json")));
        assertEquals(new Doc("new"), Doc.fromJson(com.google.gson.JsonParser.parseString(
            Files.readString(sourceFile("x.json"))).getAsJsonObject()));
        s.delete("x");
        assertFalse(Files.exists(sourceFile("x.json")));
    }

    @Test
    @DisplayName("ALWAYS without a checkout: the config write still succeeds")
    void policyAlwaysNoTree() throws IOException {
        ThreeTierStore<String, Doc> s = describe("notree")
            .sourcePolicy(SourcePolicy.ALWAYS).sourceTree(() -> null).build();
        s.save("x", new Doc("new"));
        assertTrue(s.existsInActive("x"));
        assertFalse(s.sourceTreeAvailable());
        assertThrows(IOException.class, () -> s.saveToSource("x", new Doc("explicit")));
        assertThrows(IOException.class, () -> s.promote("x"));
    }

    @Test
    @DisplayName("DEV_MODE: mirrors only while the flag is on")
    void policyDevMode() throws IOException {
        ThreeTierStore<String, Doc> s = describe("dev").sourcePolicy(SourcePolicy.DEV_MODE).build();
        s.save("x", new Doc("off"));
        assertFalse(Files.exists(sourceFile("x.json")), "dev mode off: a per-install save stays per-install");
        devMode.set(true);
        s.save("x", new Doc("on"));
        assertTrue(Files.isRegularFile(sourceFile("x.json")));
        devMode.set(false);
        s.delete("x");
        assertTrue(Files.exists(sourceFile("x.json")), "dev mode off: the mirror is not touched");
        devMode.set(true);
        s.save("x", new Doc("on"));
        s.delete("x");
        assertFalse(Files.exists(sourceFile("x.json")));
    }

    @Test
    @DisplayName("SHIPS_WITH_GAME: mirrors only when the key's .nbt is already in the source dir")
    void policyShipsWithGame() throws IOException {
        ThreeTierStore<String, Doc> s = describe("ships").sourcePolicy(SourcePolicy.SHIPS_WITH_GAME).build();
        s.save("custom", new Doc("mine"));
        assertFalse(Files.exists(sourceFile("custom.json")), "no custom.nbt in src: per-install only");
        Files.writeString(sourceFile("shipped.nbt"), "marker");
        s.save("shipped", new Doc("tweak"));
        assertTrue(Files.isRegularFile(sourceFile("shipped.json")), "shipped.nbt is there: the sidecar travels with it");
        s.delete("shipped");
        assertFalse(Files.exists(sourceFile("shipped.json")));
    }

    @Test
    @DisplayName("the default ship marker swaps the file's extension for .nbt; a custom marker overrides it")
    void shipMarker() throws IOException {
        ThreeTierStore<String, Doc> dflt = describe("marker-default")
            .fileName(id -> id + ".contents-allow.json")
            .sourcePolicy(SourcePolicy.SHIPS_WITH_GAME).build();
        Files.writeString(sourceFile("room.nbt"), "marker");
        dflt.save("room", new Doc("x"));
        assertTrue(Files.isRegularFile(sourceFile("room.contents-allow.json")));

        ThreeTierStore<String, Doc> custom = describe("marker-custom")
            .shipMarker(id -> "pool/" + id + ".nbt")
            .sourcePolicy(SourcePolicy.SHIPS_WITH_GAME).build();
        custom.save("other", new Doc("x"));
        assertFalse(Files.exists(sourceFile("other.json")));
        Files.createDirectories(sourceFile("pool"));
        Files.writeString(sourceFile("pool/other.nbt"), "marker");
        custom.save("other", new Doc("x"));
        assertTrue(Files.isRegularFile(sourceFile("other.json")));
    }

    @Test
    @DisplayName("promote copies the active file into the source tree; saveToSource writes there directly")
    void promoteAndSaveToSource() throws IOException {
        ThreeTierStore<String, Doc> s = store();
        assertThrows(IOException.class, () -> s.promote("x"), "nothing saved yet");
        s.save("x", new Doc("v1"));
        s.promote("x");
        assertEquals(Files.readString(active.resolve(SUBDIR).resolve("x.json")), Files.readString(sourceFile("x.json")));
        s.saveToSource("y", new Doc("direct"));
        assertTrue(Files.isRegularFile(sourceFile("y.json")));
        assertFalse(s.existsInActive("y"), "saveToSource is not a config save");
    }

    // ---- registry / source tree -----------------------------------------------------------------

    @Test
    @DisplayName("a store registers under its descriptor name and StoreRegistry.clearAll reaches it")
    void registry() {
        ThreeTierStore<String, Doc> s = describe("ThreeTierStoreTest.registry").build();
        assertTrue(StoreRegistry.names().contains("ThreeTierStoreTest.registry"));
        s.get("shipped");
        StoreRegistry.clearAll();
        assertFalse(s.isCached("shipped"));
    }

    @Test
    @DisplayName("SourceTree is null-safe on a root with no parent and reports availability honestly")
    void sourceTreeContract() throws IOException {
        SourceTree.Locator none = () -> null;
        assertFalse(SourceTree.available(none));
        assertTrue(SourceTree.resourcesRootOrNull(none) == null);
        assertThrows(IllegalStateException.class, () -> SourceTree.projectRoot(none));
        assertThrows(IllegalStateException.class, () -> SourceTree.resolve(none, "x"));

        SourceTree.Locator here = () -> checkout;
        assertTrue(SourceTree.available(here));
        assertEquals(checkout.resolve("src/main/resources"), SourceTree.resourcesRootOrNull(here));
        assertEquals(checkout.resolve("a/b"), SourceTree.resolve(here, "a/b"));

        Path noResources = Files.createTempDirectory("ttst");
        assertFalse(SourceTree.available(() -> noResources), "a checkout without src/main/resources is not writable-as-a-tree");
    }

    @Test
    @DisplayName("the reload barrier clears through the registry")
    void wiringIsInPlace() throws IOException {
        Path base = RepoPaths.root().resolve("src/main/java/games/brennan/dungeontrain");
        String stores = Files.readString(base.resolve("template/TemplateStores.java"), StandardCharsets.UTF_8);
        assertTrue(stores.contains("StoreRegistry.clearAll()"),
            "TemplateStores.clearCaches must loop StoreRegistry — the hand-kept list it replaced missed five caches");
        for (String hand : List.of("TrackTemplateStore", "LootPrefabStore", "BlockVariantPrefabStore",
                "CarriageContentsGroupStore", "TrackVariantGroupStore")) {
            String src = Files.readString(base.resolve("editor/" + hand + ".java"), StandardCharsets.UTF_8);
            assertTrue(src.contains("StoreRegistry.register(\"" + hand + "\""),
                hand + " keeps a cache of its own shape and must register it by hand");
        }
    }
}
