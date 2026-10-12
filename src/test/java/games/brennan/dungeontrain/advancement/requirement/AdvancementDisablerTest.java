package games.brennan.dungeontrain.advancement.requirement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Disabled advancements leave the map; their children climb to the nearest surviving ancestor. */
final class AdvancementDisablerTest {

    private static final String MOD = "dungeontrain";

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(MOD, "dungeon_train/" + name);
    }

    private static JsonObject adv(String parent) {
        JsonObject o = JsonParser.parseString("{\"display\":{\"title\":\"x\"},\"criteria\":{\"c\":{\"trigger\":\"minecraft:impossible\"}}}")
            .getAsJsonObject();
        if (parent != null) o.addProperty("parent", parent);
        return o;
    }

    /** root ← a ← b ← c, plus root ← d, plus a vanilla advancement and a non-object entry. */
    private static Map<ResourceLocation, JsonElement> tree() {
        Map<ResourceLocation, JsonElement> m = new LinkedHashMap<>();
        m.put(id("root"), adv(null));
        m.put(id("a"), adv(id("root").toString()));
        m.put(id("b"), adv(id("a").toString()));
        m.put(id("c"), adv(id("b").toString()));
        m.put(id("d"), adv(id("root").toString()));
        m.put(ResourceLocation.parse("minecraft:story/root"), adv(null));
        m.put(id("junk"), JsonParser.parseString("\"nope\""));
        return m;
    }

    private static String parentOf(Map<ResourceLocation, JsonElement> m, String name) {
        JsonElement p = m.get(id(name)).getAsJsonObject().get("parent");
        return p == null ? null : p.getAsString();
    }

    @Test
    @DisplayName("Nothing disabled — the same map instance comes back")
    void noop() {
        Map<ResourceLocation, JsonElement> in = tree();
        assertSame(in, AdvancementDisabler.removeAll(in, Set.of(), MOD));
        assertSame(in, AdvancementDisabler.removeAll(in, null, MOD));
        // An id not in the datapack, or not ours, changes nothing either.
        assertSame(in, AdvancementDisabler.removeAll(in, Set.of(id("missing"), ResourceLocation.parse("minecraft:story/root")), MOD));
    }

    @Test
    @DisplayName("Disabling a middle node re-parents its child to the node's own parent; order and the rest survive")
    void reparentsOne() {
        Map<ResourceLocation, JsonElement> in = tree();
        Map<ResourceLocation, JsonElement> out = AdvancementDisabler.removeAll(in, Set.of(id("b")), MOD);
        assertFalse(out.containsKey(id("b")));
        assertEquals(id("a").toString(), parentOf(out, "c"));
        assertEquals(id("root").toString(), parentOf(out, "a"));
        assertEquals(id("root").toString(), parentOf(out, "d"));
        assertEquals(List.of(id("root"), id("a"), id("c"), id("d"), ResourceLocation.parse("minecraft:story/root"), id("junk")),
            List.copyOf(out.keySet()));
        // The input was not mutated, and untouched entries are the same objects.
        assertTrue(in.containsKey(id("b")));
        assertEquals(id("b").toString(), parentOf(in, "c"));
        assertSame(in.get(id("a")), out.get(id("a")));
    }

    @Test
    @DisplayName("A disabled chain collapses onto the first enabled ancestor")
    void collapsesChain() {
        Map<ResourceLocation, JsonElement> out = AdvancementDisabler.removeAll(tree(), Set.of(id("a"), id("b")), MOD);
        assertFalse(out.containsKey(id("a")));
        assertFalse(out.containsKey(id("b")));
        assertEquals(id("root").toString(), parentOf(out, "c"));
    }

    @Test
    @DisplayName("A tab root is never disabled — on any tab — so its children still have somewhere to hang")
    void tabRootNeverRemoved() {
        Map<ResourceLocation, JsonElement> out = AdvancementDisabler.removeAll(tree(), Set.of(id("root"), id("a")), MOD);
        assertTrue(out.containsKey(id("root")), "the root survives");
        assertFalse(out.containsKey(id("a")));
        assertEquals(id("root").toString(), parentOf(out, "b"));
        assertEquals(id("root").toString(), parentOf(out, "d"));
    }

    @Test
    @DisplayName("Advancements on any non-editor tab can be disabled; the editor tab never")
    void anyTabButEditor() {
        Map<ResourceLocation, JsonElement> m = new LinkedHashMap<>();
        ResourceLocation photoRoot = ResourceLocation.fromNamespaceAndPath(MOD, "dungeon_train/the_enchiridion");
        ResourceLocation photo = ResourceLocation.fromNamespaceAndPath(MOD, "enchiridion/say_cheese");
        ResourceLocation editor = ResourceLocation.fromNamespaceAndPath(MOD, "editor/root");
        ResourceLocation editorChild = ResourceLocation.fromNamespaceAndPath(MOD, "editor/used_tunnel_stairs");
        m.put(photoRoot, adv(null));
        m.put(photo, adv(photoRoot.toString()));
        m.put(editor, adv(null));
        m.put(editorChild, adv(editor.toString()));
        Map<ResourceLocation, JsonElement> out = AdvancementDisabler.removeAll(m, Set.of(photo, editorChild), MOD);
        assertFalse(out.containsKey(photo));
        assertTrue(out.containsKey(editorChild));
    }
}
