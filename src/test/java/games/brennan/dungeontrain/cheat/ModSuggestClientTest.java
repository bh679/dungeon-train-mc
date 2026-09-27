package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the relay's answers to {@code POST /mods/suggest} map to what the screen shows. Pure. */
class ModSuggestClientTest {

    @Test
    @DisplayName("200 with created:true is a new suggestion, created:false backs an existing one")
    void okMapsToCreatedOrBacked() {
        assertEquals(ModSuggestClient.Result.CREATED,
            ModSuggestClient.resultOf(200, "{\"ok\":true,\"created\":true}"));
        assertEquals(ModSuggestClient.Result.BACKED,
            ModSuggestClient.resultOf(200, "{\"ok\":true,\"created\":false}"));
        assertEquals(ModSuggestClient.Result.BACKED, ModSuggestClient.resultOf(200, "{\"ok\":true}"));
    }

    @Test
    @DisplayName("A modpack or cheat submission comes back as its own result")
    void kindsMapToTheirOwnResult() {
        assertEquals(ModSuggestClient.Result.MODPACK,
            ModSuggestClient.resultOf(200, "{\"ok\":true,\"kind\":\"modpack\",\"created\":false}"));
        assertEquals(ModSuggestClient.Result.REPORTED,
            ModSuggestClient.resultOf(200, "{\"ok\":true,\"kind\":\"cheat\",\"created\":false}"));
        assertEquals(ModSuggestClient.Result.CREATED,
            ModSuggestClient.resultOf(200, "{\"ok\":true,\"kind\":\"whitelist\",\"created\":true}"));
        assertTrue(ModSuggestClient.Result.MODPACK.isFinal());
        assertTrue(ModSuggestClient.Result.REPORTED.isFinal());
    }

    @Test
    @DisplayName("A 200 that isn't ok:true is a failure, not a silent success")
    void okWithoutOkIsFailed() {
        assertEquals(ModSuggestClient.Result.FAILED, ModSuggestClient.resultOf(200, "not json"));
        assertEquals(ModSuggestClient.Result.FAILED, ModSuggestClient.resultOf(200, "{\"created\":true}"));
    }

    @Test
    @DisplayName("Each relay error code maps to its own result")
    void errorCodesMap() {
        assertEquals(ModSuggestClient.Result.NOT_PROVEN, ModSuggestClient.resultOf(401, "{\"error\":\"not_proven\"}"));
        assertEquals(ModSuggestClient.Result.ALREADY_DECIDED, ModSuggestClient.resultOf(400, "{\"error\":\"already_decided\"}"));
        assertEquals(ModSuggestClient.Result.ALREADY_LISTED, ModSuggestClient.resultOf(400, "{\"error\":\"already_listed\"}"));
        assertEquals(ModSuggestClient.Result.EXPLANATION_REQUIRED,
            ModSuggestClient.resultOf(400, "{\"error\":\"explanation_required\",\"minWords\":3}"));
        assertEquals(ModSuggestClient.Result.COMMENT_REJECTED, ModSuggestClient.resultOf(400, "{\"error\":\"comment_rejected\"}"));
        assertEquals(ModSuggestClient.Result.RATE_LIMITED, ModSuggestClient.resultOf(429, ""));
        assertEquals(ModSuggestClient.Result.FAILED, ModSuggestClient.resultOf(500, "{\"error\":\"internal\"}"));
        assertEquals(ModSuggestClient.Result.FAILED, ModSuggestClient.resultOf(404, "<html>"));
    }

    @Test
    @DisplayName("Only outcomes the player can't fix by rewording are final")
    void finality() {
        assertTrue(ModSuggestClient.Result.CREATED.isFinal());
        assertTrue(ModSuggestClient.Result.BACKED.isFinal());
        assertTrue(ModSuggestClient.Result.ALREADY_DECIDED.isFinal());
        assertTrue(ModSuggestClient.Result.ALREADY_LISTED.isFinal());
        assertFalse(ModSuggestClient.Result.NOT_PROVEN.isFinal());
        assertFalse(ModSuggestClient.Result.EXPLANATION_REQUIRED.isFinal());
        assertFalse(ModSuggestClient.Result.FAILED.isFinal());
    }
}
