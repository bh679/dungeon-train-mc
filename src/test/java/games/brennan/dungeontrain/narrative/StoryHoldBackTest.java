package games.brennan.dungeontrain.narrative;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the hold-back tier: a {@code deferred} series must never be the one a lectern starts
 * while any ordinary series is still unfinished, and must become available once they are all read.
 * Also the {@code after} chain (a series waits for its prerequisite) and the start-pick {@code weight}.
 *
 * <p>Drives the real {@link StoryRegistry} through a stub {@link ResourceManager} so the codec, the
 * registry and {@link NarrativeProgressData#randomUncompletedStory} are all exercised together —
 * that trio is where the rule actually lives.</p>
 */
final class StoryHoldBackTest {

    private static final String DIR = "narratives/stories";

    private static String storyJson(String id, boolean deferred, int letters) {
        return storyJson(id, deferred, null, 1, letters);
    }

    private static String storyJson(String id, boolean deferred, String after, double weight, int letters) {
        StringBuilder b = new StringBuilder();
        b.append("{\"id\":\"").append(id).append("\",\"character\":\"Nobody\",\"story\":\"S\"");
        if (deferred) b.append(",\"deferred\":true");
        if (after != null) b.append(",\"after\":\"").append(after).append('"');
        if (weight != 1) b.append(",\"weight\":").append(weight);
        b.append(",\"letters\":[");
        for (int i = 1; i <= letters; i++) {
            if (i > 1) b.append(',');
            b.append("{\"index\":").append(i).append(",\"label\":\"L").append(i)
             .append("\",\"variants\":[\"body\"]}");
        }
        return b.append("]}").toString();
    }

    /** Bare ResourceManager over an in-memory map — only the two methods the registry calls do work. */
    private static ResourceManager managerOf(Map<ResourceLocation, String> files) {
        return new ResourceManager() {
            private Resource res(String body) {
                return new Resource((PackResources) null,
                    () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            }

            @Override
            public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
                Map<ResourceLocation, Resource> out = new LinkedHashMap<>();
                files.forEach((id, body) -> {
                    if (id.getPath().startsWith(path) && filter.test(id)) out.put(id, res(body));
                });
                return out;
            }

            @Override
            public Optional<Resource> getResource(ResourceLocation id) {
                // No localized overlay in this fixture — the English base is always what loads.
                String body = files.get(id);
                return body == null ? Optional.empty() : Optional.of(res(body));
            }

            @Override public Set<String> getNamespaces() { return Set.of("dungeontrain"); }
            @Override public List<Resource> getResourceStack(ResourceLocation id) {
                return getResource(id).map(List::of).orElseGet(List::of);
            }
            @Override public Map<ResourceLocation, List<Resource>> listResourceStacks(
                String path, Predicate<ResourceLocation> filter) {
                Map<ResourceLocation, List<Resource>> out = new LinkedHashMap<>();
                listResources(path, filter).forEach((id, r) -> out.put(id, List.of(r)));
                return out;
            }
            @Override public java.util.stream.Stream<PackResources> listPacks() {
                return java.util.stream.Stream.empty();
            }
        };
    }

    private static ResourceLocation file(String name) {
        return ResourceLocation.fromNamespaceAndPath("dungeontrain", DIR + "/" + name + ".json");
    }

    private static Map<ResourceLocation, String> baseFixture() {
        Map<ResourceLocation, String> files = new LinkedHashMap<>();
        files.put(file("ordinary_a"), storyJson("ordinary_a", false, 2));
        files.put(file("ordinary_b"), storyJson("ordinary_b", false, 2));
        files.put(file("held_back"), storyJson("held_back", true, 2));
        return files;
    }

    @BeforeEach
    void loadFixture() {
        StoryRegistry.load(managerOf(baseFixture()));
    }

    @AfterEach
    void clearFixture() {
        NarrativeContentLocale.set("");
        StoryRegistry.clear();
    }

    private static void complete(NarrativeProgressData data, String basename, int letters) {
        for (int i = 1; i <= letters; i++) data.markRead(basename, i);
    }

    @Test
    @DisplayName("the fixture loaded, and only the held-back story carries the flag")
    void fixtureLoaded() {
        assertEquals(3, StoryRegistry.count());
        assertTrue(StoryRegistry.getByBasename("held_back").orElseThrow().deferred());
        assertFalse(StoryRegistry.getByBasename("ordinary_a").orElseThrow().deferred());
    }

    @Test
    @DisplayName("no lectern seed starts the held-back series while an ordinary one is unfinished")
    void heldBackNeverStartsFirst() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        for (long seed = -2_000L; seed <= 2_000L; seed++) {
            assertEquals(false, data.randomUncompletedStory(seed).orElseThrow().equals("held_back"),
                "seed " + seed + " started the held-back series");
        }
        // Same rule for the ordered cursor — the two sweeps must not disagree.
        assertFalse(data.nextUncompletedStory().orElseThrow().equals("held_back"));
    }

    @Test
    @DisplayName("one ordinary series left unfinished is still enough to hold the deferred one back")
    void oneOrdinaryLeftStillHolds() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        complete(data, "ordinary_a", 2);
        for (long seed = -500L; seed <= 500L; seed++) {
            assertEquals("ordinary_b", data.randomUncompletedStory(seed).orElseThrow());
        }
        assertEquals("ordinary_b", data.nextUncompletedStory().orElseThrow());
    }

    @Test
    @DisplayName("once every ordinary series is read, the held-back one is what is served")
    void heldBackServedLast() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        complete(data, "ordinary_a", 2);
        complete(data, "ordinary_b", 2);
        for (long seed = -500L; seed <= 500L; seed++) {
            assertEquals("held_back", data.randomUncompletedStory(seed).orElseThrow());
        }
        assertEquals("held_back", data.nextUncompletedStory().orElseThrow());
    }

    @Test
    @DisplayName("a localized copy that omits the flag does not un-defer the series")
    void localeOverlayCannotUnDefer() {
        // The shipped narrative_localizations story copies carry prose only — no `deferred`. Parsing
        // one alone would default it to false and hand Fourteen / Edda Marsh straight to every
        // non-English player, which is exactly what StoryRegistry.baseDeferred exists to stop.
        Map<ResourceLocation, String> files = baseFixture();
        files.put(ResourceLocation.fromNamespaceAndPath("dungeontrain",
                "narrative_localizations/es_es/stories/held_back.json"),
            storyJson("held_back", false, 2));   // translated prose, flag dropped
        NarrativeContentLocale.set("es_es");
        StoryRegistry.load(managerOf(files));

        assertTrue(StoryRegistry.getByBasename("held_back").orElseThrow().deferred(),
            "the English base's hold-back flag must survive the locale overlay");

        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        for (long seed = -500L; seed <= 500L; seed++) {
            assertFalse(data.randomUncompletedStory(seed).orElseThrow().equals("held_back"));
        }
    }

    @Test
    @DisplayName("with the whole corpus read, nothing is uncompleted — the re-read path takes over")
    void nothingLeftWhenAllComplete() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        complete(data, "ordinary_a", 2);
        complete(data, "ordinary_b", 2);
        complete(data, "held_back", 2);
        assertTrue(data.randomUncompletedStory(1234L).isEmpty());
        assertTrue(data.nextUncompletedStory().isEmpty());
    }

    // ---------------- after-chain + weight ----------------

    /** ordinary_a/b (weight 1), chain first → second → third (weight 20 each), held_back (deferred). */
    private static Map<ResourceLocation, String> chainFixture() {
        Map<ResourceLocation, String> files = new LinkedHashMap<>();
        files.put(file("chain_first"), storyJson("chain_first", false, null, 20, 2));
        files.put(file("chain_second"), storyJson("chain_second", false, "chain_first", 20, 2));
        files.put(file("chain_third"), storyJson("chain_third", false, "chain_second", 20, 2));
        files.put(file("held_back"), storyJson("held_back", true, 2));
        files.put(file("ordinary_a"), storyJson("ordinary_a", false, 2));
        files.put(file("ordinary_b"), storyJson("ordinary_b", false, 2));
        return files;
    }

    private static Map<String, Integer> pickCounts(NarrativeProgressData data, int seeds) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (long seed = 0; seed < seeds; seed++) {
            // Spread raw seeds the way a lectern's pos+worldSeed would.
            long mixed = seed * 0x9E3779B97F4A7C15L;
            counts.merge(data.randomUncompletedStory(mixed).orElseThrow(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    @DisplayName("a chained series never starts before its prerequisite is finished")
    void chainWaitsForPrerequisite() {
        StoryRegistry.load(managerOf(chainFixture()));
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        Map<String, Integer> counts = pickCounts(data, 5_000);
        assertFalse(counts.containsKey("chain_second"));
        assertFalse(counts.containsKey("chain_third"));
        assertTrue(counts.containsKey("chain_first"));

        data.markRead("chain_first", 1);   // started, not finished — still gates the next link
        assertFalse(pickCounts(data, 2_000).containsKey("chain_second"));

        complete(data, "chain_first", 2);
        counts = pickCounts(data, 5_000);
        assertTrue(counts.containsKey("chain_second"), "the next link opens once its prerequisite is read");
        assertFalse(counts.containsKey("chain_first"));
        assertFalse(counts.containsKey("chain_third"));
    }

    @Test
    @DisplayName("the chain slot carries its weight against the ordinary series")
    void chainSlotIsWeighted() {
        StoryRegistry.load(managerOf(chainFixture()));
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        Map<String, Integer> counts = pickCounts(data, 22_000);
        // Expected share 20/22 for the chain slot vs 1/22 for each ordinary series.
        int chain = counts.getOrDefault("chain_first", 0);
        assertTrue(chain > 18_500 && chain < 21_500, "chain slot picked " + chain + " / 22000");
        assertTrue(counts.getOrDefault("ordinary_a", 0) > 500, "ordinary series still get picked");
        assertTrue(counts.getOrDefault("ordinary_b", 0) > 500, "ordinary series still get picked");
    }

    @Test
    @DisplayName("the whole chain comes before the deferred tier")
    void chainPrecedesDeferred() {
        StoryRegistry.load(managerOf(chainFixture()));
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        complete(data, "ordinary_a", 2);
        complete(data, "ordinary_b", 2);
        complete(data, "chain_first", 2);
        complete(data, "chain_second", 2);
        assertEquals(Set.of("chain_third"), pickCounts(data, 1_000).keySet());
        assertEquals("chain_third", data.nextUncompletedStory().orElseThrow());
        complete(data, "chain_third", 2);
        assertEquals(Set.of("held_back"), pickCounts(data, 1_000).keySet());
        assertEquals("held_back", data.nextUncompletedStory().orElseThrow());
    }

    @Test
    @DisplayName("nextUncompletedStory never names a gated series")
    void orderedCursorRespectsChain() {
        StoryRegistry.load(managerOf(chainFixture()));
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        complete(data, "ordinary_a", 2);
        complete(data, "ordinary_b", 2);
        // chain_first unread → it, not chain_second (alphabetically later anyway) or chain_third.
        assertEquals("chain_first", data.nextUncompletedStory().orElseThrow());
    }

    @Test
    @DisplayName("an unknown prerequisite is ignored; a broken chain never stalls the world")
    void brokenChainsDoNotStall() {
        Map<ResourceLocation, String> files = new LinkedHashMap<>();
        files.put(file("orphan"), storyJson("orphan", false, "no_such_story", 1, 2));
        files.put(file("loop_a"), storyJson("loop_a", false, "loop_b", 1, 2));
        files.put(file("loop_b"), storyJson("loop_b", false, "loop_a", 1, 2));
        StoryRegistry.load(managerOf(files));
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        assertEquals(Set.of("orphan"), pickCounts(data, 500).keySet());
        complete(data, "orphan", 2);
        // Only the cycle is left — both blocked, served anyway rather than nothing.
        assertTrue(data.randomUncompletedStory(42L).isPresent());
        assertTrue(data.nextUncompletedStory().isPresent());
    }

    @Test
    @DisplayName("a localized copy that omits after/weight keeps the English base's")
    void localeOverlayKeepsChainAndWeight() {
        Map<ResourceLocation, String> files = chainFixture();
        files.put(ResourceLocation.fromNamespaceAndPath("dungeontrain",
                "narrative_localizations/es_es/stories/chain_second.json"),
            storyJson("chain_second", false, 2));   // translated prose, tuning dropped
        NarrativeContentLocale.set("es_es");
        StoryRegistry.load(managerOf(files));
        StoryFile second = StoryRegistry.getByBasename("chain_second").orElseThrow();
        assertEquals("chain_first", second.after());
        assertEquals(20.0, second.weight());
    }
}
