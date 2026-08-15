package games.brennan.dungeontrain.portal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the shipped corridor sidecar, {@code templates/portal.variants.json}.
 *
 * <p>The corridor spent a long time not applying this file at all, so nothing — not a test, not a
 * log line — would have noticed it rotting. Now that {@code PortalCarriageBuilder} rolls it, an
 * editor re-save that drops the cells or splits a lock group changes what every portal in every
 * world looks like. Same reasoning as {@code DimensionalCarriageMobsTest.shippedRoomStillHasItsMobs}: pin the
 * data, not just the code.</p>
 */
final class PortalCorridorVariantDataTest {

    private static JsonObject variants() throws Exception {
        try (InputStream in = PortalCorridorVariantDataTest.class.getResourceAsStream(
                "/data/dungeontrain/templates/portal.variants.json")) {
            assertNotNull(in, "portal.variants.json must ship");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("variants");
        }
    }

    @Test
    @DisplayName("the corridor still authors its variant cells")
    void corridorStillHasItsCells() throws Exception {
        assertFalse(variants().entrySet().isEmpty(),
            "portal.variants.json is empty — every corridor would fall back to the raw shell");
    }

    @Test
    @DisplayName("every cell sharing a lockId carries an identical state list")
    void lockGroupsAreUniform() throws Exception {
        // The precondition the picker cannot enforce for itself: each cell re-derives its bucket
        // from its OWN weights, so a group whose lists differ resolves to different blocks despite
        // the lock — which is precisely the "one corridor, two materials" report this work started
        // from. See CarriageVariantBlocksLockGroupTest#diverging_weightsBreakTheGroup.
        Map<Integer, String> signatureByLock = new LinkedHashMap<>();
        Map<Integer, String> firstCellByLock = new LinkedHashMap<>();
        List<String> offenders = new ArrayList<>();

        for (Map.Entry<String, JsonElement> cell : variants().entrySet()) {
            JsonElement value = cell.getValue();
            if (!value.isJsonObject()) continue;      // bare array = unlocked, nothing to agree with
            JsonObject obj = value.getAsJsonObject();
            if (!obj.has("lockId")) continue;
            int lockId = obj.get("lockId").getAsInt();
            if (lockId <= 0) continue;

            String signature = signatureOf(obj.getAsJsonArray("states"));
            String seen = signatureByLock.putIfAbsent(lockId, signature);
            firstCellByLock.putIfAbsent(lockId, cell.getKey());
            if (seen != null && !seen.equals(signature)) {
                offenders.add("lock " + lockId + ": cell " + cell.getKey() + " authored "
                    + signature + " but cell " + firstCellByLock.get(lockId) + " authored " + seen);
            }
        }

        assertFalse(signatureByLock.isEmpty(), "the corridor authors no lock groups at all");
        assertEquals(List.of(), offenders, "lock groups must be uniform to render as one block");
    }

    /** State name + weight, in order — the two things the picker's bucket walk depends on. */
    private static String signatureOf(com.google.gson.JsonArray states) {
        List<String> parts = new ArrayList<>();
        for (JsonElement state : states) {
            if (state.isJsonPrimitive()) {
                parts.add(state.getAsString() + "@1");
            } else {
                JsonObject obj = state.getAsJsonObject();
                int weight = obj.has("weight") ? obj.get("weight").getAsInt() : 1;
                parts.add(obj.get("state").getAsString() + "@" + weight);
            }
        }
        return String.join(" | ", parts);
    }
}
