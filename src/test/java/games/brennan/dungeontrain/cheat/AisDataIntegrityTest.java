package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deviation detection for {@link AisDataIntegrity}: the check must mirror AIS
 * 0.7.0's own {@code AisConfig.parse} semantics exactly — effective-value
 * comparison with per-key fallback — so a config AIS would treat as defaults is
 * never flagged, and only values AIS would actually apply differently are.
 */
class AisDataIntegrityTest {

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    // ---- Clean configs ---------------------------------------------------

    @Test
    @DisplayName("Empty properties (all keys absent) ⇒ defaults ⇒ clean")
    void emptyIsClean() {
        assertTrue(AisDataIntegrity.deviationsOf(props()).isEmpty());
    }

    @Test
    @DisplayName("Explicit defaults ⇒ clean")
    void explicitDefaultsAreClean() {
        assertTrue(AisDataIntegrity.deviationsOf(props(
            "raiseAttributeCaps", "true",
            "armorCapMax", "1024.0",
            "armorToughnessCapMax", "1024.0")).isEmpty());
    }

    @Test
    @DisplayName("Numeric formatting variants of the default value ⇒ clean")
    void numericFormattingIsClean() {
        assertTrue(AisDataIntegrity.deviationsOf(props("armorCapMax", "1024")).isEmpty());
        assertTrue(AisDataIntegrity.deviationsOf(props("armorCapMax", " 1024.00 ")).isEmpty());
        assertTrue(AisDataIntegrity.deviationsOf(props("raiseAttributeCaps", " TRUE ")).isEmpty());
    }

    @Test
    @DisplayName("Malformed values fall back per-key like AIS ⇒ clean")
    void malformedValuesAreClean() {
        assertTrue(AisDataIntegrity.deviationsOf(props(
            "raiseAttributeCaps", "yes",
            "armorCapMax", "not-a-number",
            "armorToughnessCapMax", "-5")).isEmpty());
        // Non-finite and zero values also fall back in AIS
        assertTrue(AisDataIntegrity.deviationsOf(props("armorCapMax", "Infinity")).isEmpty());
        assertTrue(AisDataIntegrity.deviationsOf(props("armorToughnessCapMax", "0")).isEmpty());
    }

    @Test
    @DisplayName("Unknown keys are ignored (future AIS versions may add keys)")
    void unknownKeysIgnored() {
        assertTrue(AisDataIntegrity.deviationsOf(props(
            "someFutureKey", "whatever",
            "raiseAttributeCaps", "true")).isEmpty());
    }

    // ---- Deviations ------------------------------------------------------

    @Test
    @DisplayName("raiseAttributeCaps=false deviates")
    void raiseCapsDeviates() {
        List<String> d = AisDataIntegrity.deviationsOf(props("raiseAttributeCaps", "false"));
        assertEquals(1, d.size());
        assertTrue(d.get(0).contains("raiseAttributeCaps"));
    }

    @Test
    @DisplayName("Non-default armorCapMax deviates")
    void armorCapDeviates() {
        List<String> d = AisDataIntegrity.deviationsOf(props("armorCapMax", "99999"));
        assertEquals(1, d.size());
        assertTrue(d.get(0).contains("armorCapMax"));
    }

    @Test
    @DisplayName("Non-default armorToughnessCapMax deviates")
    void toughnessCapDeviates() {
        List<String> d = AisDataIntegrity.deviationsOf(props("armorToughnessCapMax", "30"));
        assertEquals(1, d.size());
        assertTrue(d.get(0).contains("armorToughnessCapMax"));
    }

    @Test
    @DisplayName("Multiple deviations are all reported")
    void multipleDeviations() {
        List<String> d = AisDataIntegrity.deviationsOf(props(
            "raiseAttributeCaps", "false",
            "armorCapMax", "2048",
            "armorToughnessCapMax", "512"));
        assertEquals(3, d.size());
    }

    // ---- File-level semantics -------------------------------------------

    @Test
    @DisplayName("Missing config file ⇒ clean (AIS uses defaults)")
    void missingFileIsClean(@TempDir Path dir) {
        assertTrue(AisDataIntegrity.check(dir).isEmpty());
    }

    @Test
    @DisplayName("Config file on disk is parsed and flagged")
    void fileOnDiskIsChecked(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(AisDataIntegrity.FILE_NAME),
            "# tweaked\nraiseAttributeCaps=false\n");
        List<String> d = AisDataIntegrity.check(dir);
        assertEquals(1, d.size());
        assertTrue(d.get(0).contains("raiseAttributeCaps"));
    }

    @Test
    @DisplayName("Default-content file on disk ⇒ clean")
    void defaultFileIsClean(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(AisDataIntegrity.FILE_NAME),
            "raiseAttributeCaps=true\narmorCapMax=1024.0\narmorToughnessCapMax=1024.0\n");
        assertTrue(AisDataIntegrity.check(dir).isEmpty());
    }
}
