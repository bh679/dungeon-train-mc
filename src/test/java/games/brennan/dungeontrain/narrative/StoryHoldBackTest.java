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
 *
 * <p>Drives the real {@link StoryRegistry} through a stub {@link ResourceManager} so the codec, the
 * registry and {@link NarrativeProgressData#randomUncompletedStory} are all exercised together —
 * that trio is where the rule actually lives.</p>
 */
final class StoryHoldBackTest {

    private static final String DIR = "narratives/stories";

    private static String storyJson(String id, boolean deferred, int letters) {
        StringBuilder b = new StringBuilder();
        b.append("{\"id\":\"").append(id).append("\",\"character\":\"Nobody\",\"story\":\"S\"");
        if (deferred) b.append(",\"deferred\":true");
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

    @BeforeEach
    void loadFixture() {
        Map<ResourceLocation, String> files = new LinkedHashMap<>();
        files.put(file("ordinary_a"), storyJson("ordinary_a", false, 2));
        files.put(file("ordinary_b"), storyJson("ordinary_b", false, 2));
        files.put(file("held_back"), storyJson("held_back", true, 2));
        StoryRegistry.load(managerOf(files));
    }

    @AfterEach
    void clearFixture() {
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
    @DisplayName("with the whole corpus read, nothing is uncompleted — the re-read path takes over")
    void nothingLeftWhenAllComplete() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        complete(data, "ordinary_a", 2);
        complete(data, "ordinary_b", 2);
        complete(data, "held_back", 2);
        assertTrue(data.randomUncompletedStory(1234L).isEmpty());
        assertTrue(data.nextUncompletedStory().isEmpty());
    }
}
