package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotemJourneyAdvancementsTest {

    @Test
    @DisplayName("Undead Commuter needs the full ride between turning and the cure")
    void commuteThreshold() {
        assertTrue(TotemJourneyAdvancements.commuted(200, 1200, 1000));
        assertFalse(TotemJourneyAdvancements.commuted(200, 1199, 1000));
        assertFalse(TotemJourneyAdvancements.commuted(-1, 5000, 1000)); // never recorded as a zombie
    }

    @Test
    @DisplayName("A held stack round-trips through its attachment string, and junk reads as none")
    void heldStackEncoding() {
        var stack = new TotemJourneyAdvancements.HeldStack(ResourceLocation.parse("dungeontotems:rage_totem"), 42);
        assertEquals(stack, TotemJourneyAdvancements.HeldStack.parse(stack.encode()));
        assertNull(TotemJourneyAdvancements.HeldStack.parse(""));
        assertNull(TotemJourneyAdvancements.HeldStack.parse("dungeontotems:rage_totem@x"));
    }

    @Test
    @DisplayName("Each stack advancement listens for its own action, and the commuter for its")
    void jsonActionsMatchCode() throws Exception {
        for (Map.Entry<ResourceLocation, ResourceLocation> e : TotemJourneyAdvancements.STACK_ADVANCEMENTS.entrySet()) {
            assertEquals(TotemJourneyAdvancements.stackAction(e.getKey()), actionOf(e.getValue()));
        }
        assertEquals(TotemJourneyAdvancements.COMMUTE_ACTION, actionOf(TotemJourneyAdvancements.UNDEAD_COMMUTER));
    }

    @Test
    @DisplayName("Menagerie of One needs exactly the forms Dungeon Totems' Totem of Morphing can pick")
    void menagerieMatchesMorphForms() throws Exception {
        // Read through a Dungeon Totems class: its jar is its own module, so DT's class loader cannot see it.
        Set<String> forms = json(games.brennan.dungeontotems.morph.MorphForm.class,
            "/data/dungeontotems/data_maps/entity_type/morph_forms.json").getAsJsonObject("values").keySet();
        assertEquals(forms, criteria("dungeon_train/menagerie_of_one").keySet());
    }

    @Test
    @DisplayName("Totem Pole needs exactly the totems DT's loot can hand out")
    void totemPoleMatchesTotemTable() throws Exception {
        Set<String> totems = json("/data/dungeontrain/loot/random_totem.json").getAsJsonObject("weights").keySet();
        Set<String> needed = new TreeSet<>();
        for (Map.Entry<String, JsonElement> c : criteria("dungeon_train/totem_pole").entrySet()) {
            needed.add(c.getValue().getAsJsonObject().getAsJsonObject("conditions")
                .getAsJsonObject("item").get("items").getAsString());
        }
        assertEquals(new TreeSet<>(totems), needed);
    }

    private static String actionOf(ResourceLocation advancement) throws Exception {
        return criteria(advancement.getPath()).entrySet().iterator().next().getValue().getAsJsonObject()
            .getAsJsonObject("conditions").get("actionId").getAsString();
    }

    private static JsonObject criteria(String path) throws Exception {
        return json("/data/dungeontrain/advancement/" + path + ".json").getAsJsonObject("criteria");
    }

    private static JsonObject json(String path) throws Exception {
        return json(TotemJourneyAdvancementsTest.class, path);
    }

    private static JsonObject json(Class<?> owner, String path) throws Exception {
        var in = owner.getResourceAsStream(path);
        assertNotNull(in, path);
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }
}
