package games.brennan.dungeontrain.editor.workbench;

import com.google.gson.JsonObject;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The staged build record: its id rule and the JSON the shelf writes for it. */
final class WorkbenchStagedBuildTest {

    @Test
    @DisplayName("a staged id is the lower-cased, sanitised name plus the relay id — never blank, never too long")
    void idFor() {
        assertEquals("brick-cabin-4271", WorkbenchStagedBuild.idFor("Brick Cabin", 4271));
        assertEquals("build-7", WorkbenchStagedBuild.idFor("!!!", 7));
        assertEquals("build-7", WorkbenchStagedBuild.idFor(null, 7));
        String longName = "x".repeat(200);
        String id = WorkbenchStagedBuild.idFor(longName, 123456);
        assertTrue(id.length() <= WorkbenchStagedBuild.MAX_ID_LENGTH, id);
        assertTrue(id.endsWith("-123456"), id);
    }

    @Test
    @DisplayName("the metadata JSON carries every field the record has, and the prefabs map survives")
    void metaJsonRoundTrip() {
        WorkbenchStagedBuild build = new WorkbenchStagedBuild("brick-cabin-4271", 4271, "contents", "", "Brick Cabin",
            "desert", "0000-1111", "Alex", true, new Vec3i(11, 7, 13), "{\"files\":{}}",
            Map.of("gold_hoard", "{\"x\":1}"), 1700000000000L);
        JsonObject meta = WorkbenchStagingStore.metaJson(build);
        assertEquals("brick-cabin-4271", meta.get("stagedId").getAsString());
        assertEquals(4271, meta.get("relayId").getAsInt());
        assertEquals("contents", meta.get("kind").getAsString());
        assertEquals("Brick Cabin", meta.get("buildName").getAsString());
        assertEquals("desert", meta.get("stage").getAsString());
        assertEquals("Alex", meta.get("ownerName").getAsString());
        assertTrue(meta.get("mine").getAsBoolean());
        assertEquals(11, meta.get("l").getAsInt());
        assertEquals(7, meta.get("h").getAsInt());
        assertEquals(13, meta.get("w").getAsInt());
        assertEquals(1700000000000L, meta.get("stagedAt").getAsLong());

        Map<String, String> prefabs = WorkbenchStagingStore.prefabsOf(WorkbenchStagingStore.prefabsJson(build.lootPrefabs()));
        assertEquals(build.lootPrefabs(), prefabs);
    }

    @Test
    @DisplayName("withSize keeps everything but the footprint")
    void withSize() {
        WorkbenchStagedBuild build = new WorkbenchStagedBuild("a-1", 1, "building", "", "A", "", "u", "n", false,
            new Vec3i(1, 1, 1), "", Map.of(), 5L);
        WorkbenchStagedBuild resized = build.withSize(new Vec3i(3, 4, 5));
        assertEquals(new Vec3i(3, 4, 5), resized.size());
        assertEquals(build.stagedId(), resized.stagedId());
        assertEquals(build.relayKind(), resized.relayKind());
        assertEquals(build.stagedAt(), resized.stagedAt());
    }
}
