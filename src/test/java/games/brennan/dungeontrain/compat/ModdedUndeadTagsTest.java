package games.brennan.dungeontrain.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luminous Monsters' zombies and skeletons (and BetterNether's Jungle Skeleton) extend vanilla's
 * classes but ship no {@code minecraft:} entity tags, and 1.21.1 decides "undead" (Smite, inverted
 * Healing/Harming, poison immunity) by tag alone. DT adds them to {@code #minecraft:zombies} /
 * {@code #minecraft:skeletons}, which feed {@code #minecraft:undead}; BetterNether's floating Skull
 * goes into {@code #minecraft:undead} directly. The Luminous lists are every entity in Luminous
 * Monsters 1.3.1 (all extend {@code Zombie} or {@code Skeleton}); a Luminous bump that adds a
 * variant must add it here too.
 */
final class ModdedUndeadTagsTest {

    private static final Set<String> SKELETONS = Set.of(
        "luminous_monsters:cherry_skeleton", "luminous_monsters:dark_oak_skeleton",
        "luminous_monsters:hollow_skeleton", "luminous_monsters:icy_skeleton",
        "luminous_monsters:miner_skeleton", "luminous_monsters:revenant_skeleton",
        "luminous_monsters:savanna_skeleton", "luminous_monsters:sunken_skeleton",
        "luminous_monsters:swamp_skeleton",
        "betternether:jungle_skeleton");

    private static final Set<String> ZOMBIES = Set.of(
        "luminous_monsters:cherry_zombie", "luminous_monsters:crimson_walker",
        "luminous_monsters:dark_oak_zombie", "luminous_monsters:icy_zombie",
        "luminous_monsters:miner_zombie", "luminous_monsters:savanna_zombie",
        "luminous_monsters:swamp_zombie");

    /** Not a zombie or skeleton, but a floating skull: undead directly. */
    private static final Set<String> OTHER_UNDEAD = Set.of("betternether:skull");

    @Test
    @DisplayName("every modded zombie and skeleton joins vanilla's zombies, skeletons or undead tag")
    void everyLuminousMobIsUndead() throws Exception {
        assertEquals(SKELETONS, tagValues("skeletons"));
        assertEquals(ZOMBIES, tagValues("zombies"));
        assertEquals(OTHER_UNDEAD, tagValues("undead"));
    }

    private static Set<String> tagValues(String tag) throws Exception {
        JsonObject json = read("/data/minecraft/tags/entity_type/" + tag + ".json");
        assertFalse(json.get("replace").getAsBoolean(), tag + " must not replace vanilla's list");
        Set<String> ids = new HashSet<>();
        for (JsonElement entry : json.getAsJsonArray("values")) {
            JsonObject o = entry.getAsJsonObject();
            assertFalse(o.get("required").getAsBoolean(), tag + ": a missing mod must not break the tag");
            ids.add(o.get("id").getAsString());
        }
        return ids;
    }

    private static JsonObject read(String path) throws Exception {
        try (InputStream in = ModdedUndeadTagsTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
