package games.brennan.dungeontrain.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * The wire contract between the mod and the relay's {@code /progress/state} endpoint. These assertions
 * are the mod's half of a two-repo agreement — the relay's half lives in {@code dp-relay/progress.test.js}
 * — so a change on either side that breaks the shape fails a test rather than silently syncing nothing.
 */
class ProgressBlobTest {

    @Test
    void kindPrefixDeclaresTheMergeStrategy() {
        assertEquals("set", ProgressBlob.set(ProgressBlob.ADVANCEMENTS, List.of("a")).strategy());
        assertEquals("max", ProgressBlob.max(ProgressBlob.STATS, Map.of("x", 1.0)).strategy());
        assertEquals("lww", ProgressBlob.lww(ProgressBlob.enderChest("survival"), 1, "AAAA").strategy());
    }

    @Test
    void enderChestKindNamesTheEcpSlot() {
        // ECP keys its stored chests by game-mode name; the blob kind carries that through so a
        // survival chest can never be restored into an adventure slot.
        assertEquals("lww:enderchest.survival", ProgressBlob.enderChest("survival"));
        assertEquals("lww:enderchest.adventure", ProgressBlob.enderChest("adventure"));
    }

    @Test
    void setBlobSerialisesIdsWithoutASeq() {
        JsonObject o = ProgressBlob.set(ProgressBlob.ADVANCEMENTS,
                List.of("minecraft:story/mine_stone", "dungeontrain:carts_100")).toJson();
        assertEquals("set:advancements", o.get("kind").getAsString());
        assertEquals(2, o.getAsJsonArray("ids").size());
        // A union has no ordering to guard, so sending a seq would be meaningless.
        assertTrue(!o.has("seq"), "set blobs must not carry a seq");
    }

    @Test
    void lwwBlobCarriesTheSeqThatGuardsIt() {
        JsonObject o = ProgressBlob.lww(ProgressBlob.enderChest("survival"), 7, "QUFB").toJson();
        assertEquals(7, o.get("seq").getAsInt());
        assertEquals("QUFB", o.get("data").getAsString());
    }

    @Test
    void parsesEachStrategyBackFromTheRelayResponse() {
        ProgressBlob set = ProgressBlob.fromJson(JsonParser
                .parseString("{\"kind\":\"set:advancements\",\"seq\":3,\"ids\":[\"a\",\"b\"]}").getAsJsonObject());
        assertEquals(List.of("a", "b"), set.ids());

        ProgressBlob max = ProgressBlob.fromJson(JsonParser
                .parseString("{\"kind\":\"max:stats\",\"seq\":2,\"fields\":{\"totalDeaths\":4}}").getAsJsonObject());
        assertEquals(4.0, max.fields().get("totalDeaths"));

        ProgressBlob lww = ProgressBlob.fromJson(JsonParser
                .parseString("{\"kind\":\"lww:enderchest.survival\",\"seq\":9,\"data\":\"QUFB\"}").getAsJsonObject());
        assertEquals(9, lww.seq());
        assertEquals("QUFB", lww.data());
    }

    @Test
    void unusableJsonParsesToNullRatherThanAnEmptyBlob() {
        // An empty blob would read as "the relay says you have nothing", which for a chest would mean
        // wiping it. Null makes the caller skip instead.
        assertNull(ProgressBlob.fromJson(null));
        assertNull(ProgressBlob.fromJson(new JsonObject()));
        assertNull(ProgressBlob.fromJson(JsonParser.parseString("{\"kind\":\"lww:chest\"}").getAsJsonObject()));
    }

    @Test
    void credentialNeverPrintsItsSecret() {
        ProgressCredential c = new ProgressCredential("12", ProgressCredential.KIND_TOKEN, "abc",
                "super-secret-value", null, "Steve");
        assertTrue(c.isUsable());
        assertTrue(!c.isVerified());
        assertTrue(!c.toString().contains("super-secret-value"),
                "the secret must never reach a log line or crash report");
    }

    @Test
    void credentialWithoutASecretIsNotUsable() {
        assertTrue(!new ProgressCredential("12", "token", "abc", "", null, null).isUsable());
        assertTrue(!new ProgressCredential(null, "token", "abc", "s", null, null).isUsable());
    }
}
