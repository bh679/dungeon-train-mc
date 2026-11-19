package games.brennan.dungeontrain.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seal every progress file carries: what verifies, what is unsealed, what is tampered. */
class SealedJsonTest {

    private static final String SCOPE = SealedJson.scope("test", UUID.fromString("12345678-1234-1234-1234-123456789abc"), "normal");

    private Path dir;

    @BeforeEach
    void keyInTempDir(@TempDir Path dir) {
        this.dir = dir;
        InstallKey.useDirectoryForTests(dir);
    }

    @AfterEach
    void restoreKey() {
        InstallKey.resetForTests();
    }

    private static JsonObject payload(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    @DisplayName("a sealed object verifies, and the seal ignores field order and whitespace")
    void sealedVerifies() {
        JsonObject sealed = SealedJson.seal(payload("{\"a\":[1,2],\"b\":{\"y\":1,\"x\":2}}"), SCOPE, false);
        assertTrue(sealed.has("seal"));
        assertFalse(sealed.has("tainted"));
        SealedJson.Verdict verdict = SealedJson.verify(sealed, SCOPE);
        assertEquals(SealedJson.Provenance.VERIFIED, verdict.provenance());
        assertFalse(verdict.tainted());

        JsonObject reordered = payload("{ \"b\": {\"x\": 2, \"y\": 1}, \"a\": [1, 2], \"seal\": \"" + sealed.get("seal").getAsString() + "\" }");
        assertEquals(SealedJson.Provenance.VERIFIED, SealedJson.verify(reordered, SCOPE).provenance());
    }

    @Test
    @DisplayName("any change to the contents, or to the scope, is tampered")
    void changedIsTampered() {
        JsonObject sealed = SealedJson.seal(payload("{\"a\":[1,2]}"), SCOPE, false);
        JsonObject edited = sealed.deepCopy();
        edited.getAsJsonArray("a").add(3);
        assertEquals(SealedJson.Provenance.TAMPERED, SealedJson.verify(edited, SCOPE).provenance());
        assertEquals(SealedJson.Provenance.TAMPERED, SealedJson.verify(sealed, SCOPE + "x").provenance());
        JsonObject untainted = sealed.deepCopy();
        untainted.addProperty("tainted", false);
        assertEquals(SealedJson.Provenance.TAMPERED, SealedJson.verify(untainted, SCOPE).provenance(),
            "the taint flag is under the seal, so it cannot be cleared by hand");
    }

    @Test
    @DisplayName("the taint mark rides under the seal and reads back")
    void taintPersists() {
        JsonObject sealed = SealedJson.seal(payload("{\"a\":1}"), SCOPE, true);
        SealedJson.Verdict verdict = SealedJson.verify(sealed, SCOPE);
        assertEquals(SealedJson.Provenance.VERIFIED, verdict.provenance());
        assertTrue(verdict.tainted());
    }

    @Test
    @DisplayName("an unsealed object is this install's own before its first seal, and unverified after")
    void unsealedDependsOnHistory() throws Exception {
        assertEquals(SealedJson.Provenance.VERIFIED, SealedJson.verify(payload("{\"a\":1}"), SCOPE).provenance());
        SealedJson.seal(payload("{}"), SCOPE, false);          // creates the key during this session
        assertEquals(SealedJson.Provenance.VERIFIED, SealedJson.verify(payload("{\"a\":1}"), SCOPE).provenance(),
            "the answer is fixed for the session that made the key");
        assertTrue(Files.isRegularFile(dir.resolve(InstallKey.FILE_NAME)));
        InstallKey.useDirectoryForTests(dir);                   // the next session finds the key
        assertEquals(SealedJson.Provenance.UNVERIFIED, SealedJson.verify(payload("{\"a\":1}"), SCOPE).provenance());
    }

    @Test
    @DisplayName("the key survives a session and still verifies what it sealed")
    void keyIsStable() {
        JsonObject sealed = SealedJson.seal(payload("{\"a\":1}"), SCOPE, false);
        InstallKey.useDirectoryForTests(dir);
        assertEquals(SealedJson.Provenance.VERIFIED, SealedJson.verify(sealed, SCOPE).provenance());
    }
}
