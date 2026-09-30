package games.brennan.dungeontrain.block.stage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.block.stage.StageStoneFamily.StoneKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage fence / wall placeholders only join their neighbours when they carry the vanilla
 * connection tags — {@code FenceBlock#isSameFence} reads {@code minecraft:fences} (via
 * {@code wooden_fences}), {@code WallBlock#connectsTo} reads {@code minecraft:walls}.
 */
final class StagePlaceholderTagsTest {

    @Test
    @DisplayName("stage_fence is a wooden fence, so stage fences connect to each other")
    void fenceTagged() throws IOException {
        assertTrue(values("wooden_fences").contains("dungeontrain:stage_fence"));
    }

    @Test
    @DisplayName("every stage stone wall is in minecraft:walls")
    void wallsTagged() throws IOException {
        Set<String> walls = values("walls");
        for (StoneKind kind : StoneKind.values()) {
            String base = kind == StoneKind.STONE ? "stage_stone" : "stage_stone_" + kind.id();
            String id = "dungeontrain:" + base + "_wall";
            assertTrue(walls.contains(id), id + " missing from minecraft:walls");
        }
    }

    private static Set<String> values(String tag) throws IOException {
        String json = Files.readString(RepoPaths.resources()
            .resolve("data/minecraft/tags/block/" + tag + ".json"));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(root.has("replace") && root.get("replace").getAsBoolean(),
            tag + " must not replace the vanilla tag");
        Set<String> out = new HashSet<>();
        for (JsonElement e : root.getAsJsonArray("values")) out.add(e.getAsString());
        return out;
    }
}
