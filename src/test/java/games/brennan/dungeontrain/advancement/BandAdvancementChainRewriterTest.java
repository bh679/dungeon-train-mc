package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Parents follow the chain, missing members are skipped over, nothing else moves, input untouched. */
final class BandAdvancementChainRewriterTest {

    private static final String MOD = "dungeontrain";

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(MOD, "dungeon_train/" + name);
    }

    private static JsonElement adv(String parent) {
        return JsonParser.parseString("{\"parent\":\"dungeontrain:dungeon_train/" + parent
                + "\",\"criteria\":{\"reached\":{\"trigger\":\"dungeontrain:gameplay_action\"}}}");
    }

    private static String parentOf(Map<ResourceLocation, JsonElement> map, String name) {
        return map.get(id(name)).getAsJsonObject().get("parent").getAsString();
    }

    @Test
    @DisplayName("each member parents to the one before; the first to the anchor; others untouched")
    void rewritesChain() {
        Map<ResourceLocation, JsonElement> in = new LinkedHashMap<>();
        in.put(id("carts_100"), adv("root"));
        in.put(id("reached_nether"), adv("carts_100"));
        in.put(id("reached_stacks"), adv("reached_nether"));
        in.put(id("reached_spheres"), adv("reached_stacks"));
        in.put(id("other"), adv("reached_spheres"));

        Map<ResourceLocation, JsonElement> out = BandAdvancementChainRewriter.rewriteParents(
                in, List.of("reached_spheres", "reached_stacks", "reached_nether"), "carts_100", MOD);

        assertEquals("dungeontrain:dungeon_train/carts_100", parentOf(out, "reached_spheres"));
        assertEquals("dungeontrain:dungeon_train/reached_spheres", parentOf(out, "reached_stacks"));
        assertEquals("dungeontrain:dungeon_train/reached_stacks", parentOf(out, "reached_nether"));
        assertSame(in.get(id("other")), out.get(id("other")));
        assertSame(in.get(id("carts_100")), out.get(id("carts_100")));
        assertEquals(List.copyOf(in.keySet()), List.copyOf(out.keySet()));
        // input untouched
        assertEquals("dungeontrain:dungeon_train/carts_100", parentOf(in, "reached_nether"));
    }

    @Test
    @DisplayName("a chain member missing from the datapack is skipped over, not left as a gap")
    void missingMemberSkipped() {
        Map<ResourceLocation, JsonElement> in = new LinkedHashMap<>();
        in.put(id("reached_nether"), adv("carts_100"));
        in.put(id("reached_stacks"), adv("reached_nether"));

        Map<ResourceLocation, JsonElement> out = BandAdvancementChainRewriter.rewriteParents(
                in, List.of("reached_nether", "reached_ghost", "reached_stacks"), "carts_100", MOD);

        assertEquals("dungeontrain:dungeon_train/reached_nether", parentOf(out, "reached_stacks"));
        assertEquals(2, out.size());
    }
}
