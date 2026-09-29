package games.brennan.dungeontrain.template;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Template-group memberships on {@link TemplateMeta} and their {@code weights.json} form. */
final class TemplateGroupsCodecTest {

    @Test
    @DisplayName("an entry in several groups round-trips through weights.json")
    void roundTrip() {
        TemplateMeta meta = TemplateMeta.of(3).withGroups(List.of("stone", "brick"));
        JsonObject json = TemplateWeightCodec.toJson(Map.of("arch", meta));
        TemplateMeta back = TemplateWeightCodec.parseEntry(json.get("arch"), w -> w);
        assertEquals(List.of("brick", "stone"), back.groups());
        assertEquals(3, back.weight());
    }

    @Test
    @DisplayName("an ungrouped default entry still writes as a bare int")
    void bareIntUnchanged() {
        JsonObject json = TemplateWeightCodec.toJson(Map.of("plain", TemplateMeta.of(2)));
        assertTrue(json.get("plain").isJsonPrimitive());
    }

    @Test
    @DisplayName("group ids are lowercased, de-duplicated, sorted and invalid ones dropped")
    void normalised() {
        TemplateMeta meta = TemplateMeta.of(1).withGroups(Arrays.asList("Stone", "stone", "bad id", "", null, "a_1"));
        assertEquals(List.of("a_1", "stone"), meta.groups());
    }

    @Test
    @DisplayName("a malformed groups field is ignored rather than failing the entry")
    void malformedIgnored() {
        TemplateMeta meta = TemplateWeightCodec.parseEntry(
            JsonParser.parseString("{\"weight\":4,\"groups\":\"stone\"}"), w -> w);
        assertEquals(4, meta.weight());
        assertFalse(meta.hasGroups());
    }

    @Test
    @DisplayName("every other edit keeps the memberships")
    void editsKeepGroups() {
        TemplateMeta meta = TemplateMeta.of(1).withGroups(List.of("stone"));
        assertEquals(List.of("stone"), meta.withWeight(9).withStage("s").withName("Arch").asCopy().groups());
    }

    @Test
    @DisplayName("UNGROUPED matches only templates in no group; a named group matches its members")
    void groupMatching() {
        assertTrue(TemplateGroup.UNGROUPED.matches(List.of()));
        assertFalse(TemplateGroup.UNGROUPED.matches(List.of("stone")));
        assertTrue(TemplateGroup.of("stone").matches(List.of("brick", "stone")));
        assertFalse(TemplateGroup.of("stone").matches(List.of()));
    }
}
