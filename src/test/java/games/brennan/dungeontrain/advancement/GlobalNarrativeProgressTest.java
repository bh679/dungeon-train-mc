package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the JSON schema of the cross-world {@link GlobalNarrativeProgress}
 * store via its {@link GlobalNarrativeProgress.Data} codec. The public
 * mark/read methods touch {@code FMLPaths.CONFIGDIR} (not available in a plain
 * unit test), so these tests exercise the serialization layer directly — the
 * part most likely to break on a field rename or map-shape change.
 */
final class GlobalNarrativeProgressTest {

    @Test
    @DisplayName("Data codec round-trips read-letters and variants-seen maps")
    void codecRoundTrip() {
        GlobalNarrativeProgress.Data original = new GlobalNarrativeProgress.Data(
            Map.of(
                "augustus_park", List.of(1, 2, 3),
                "pip_aaro_the_waiting_child", List.of(1)
            ),
            Map.of(
                "augustus_park#3", List.of(0, 1),
                "pip_aaro_the_waiting_child#1", List.of(0)
            )
        );

        JsonElement json = GlobalNarrativeProgress.Data.CODEC
            .encodeStart(JsonOps.INSTANCE, original).result().orElseThrow();
        GlobalNarrativeProgress.Data decoded = GlobalNarrativeProgress.Data.CODEC
            .parse(JsonOps.INSTANCE, json).result().orElseThrow();

        assertEquals(original.readLetters(), decoded.readLetters(),
            "read-letters map must survive encode→decode");
        assertEquals(original.variantsSeen(), decoded.variantsSeen(),
            "variants-seen map must survive encode→decode");
    }

    @Test
    @DisplayName("Empty/legacy JSON object decodes to empty Data (optional fields)")
    void emptyDecodesEmpty() {
        JsonElement empty = JsonParser.parseString("{}");
        GlobalNarrativeProgress.Data decoded = GlobalNarrativeProgress.Data.CODEC
            .parse(JsonOps.INSTANCE, empty).result().orElseThrow();

        assertTrue(decoded.readLetters().isEmpty(), "missing read_letters → empty, not error");
        assertTrue(decoded.variantsSeen().isEmpty(), "missing variants_seen → empty, not error");
    }
}
