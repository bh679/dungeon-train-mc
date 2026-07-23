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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    @DisplayName("Expectation is read from the installed AIS build itself — its own defaults are always clean")
    void expectationMatchesInstalledAis() {
        games.brennan.adventureitemstats.internal.AisConfig defaults =
            games.brennan.adventureitemstats.internal.AisConfig.defaults();
        assertTrue(AisDataIntegrity.deviationsOf(props(
            "raiseAttributeCaps", String.valueOf(defaults.raiseAttributeCaps()),
            "armorCapMax", String.valueOf(defaults.armorCapMax()),
            "armorToughnessCapMax", String.valueOf(defaults.armorToughnessCapMax()))).isEmpty());
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
    @DisplayName("restoreDefaults rewrites a tampered file back to clean, backing up the original")
    void restoreDefaultsRoundTrip(@TempDir Path dir) throws IOException {
        String tampered = "raiseAttributeCaps=false\narmorCapMax=99999\n";
        Files.writeString(dir.resolve(AisDataIntegrity.FILE_NAME), tampered);
        assertEquals(2, AisDataIntegrity.check(dir).size());
        AisDataIntegrity.RestoreResult result = AisDataIntegrity.restoreDefaults(dir);
        assertTrue(result.success());
        assertTrue(AisDataIntegrity.check(dir).isEmpty());
        // The replaced file's exact content is preserved in the backup.
        assertNotNull(result.backup());
        assertTrue(result.backup().getFileName().toString()
            .startsWith(AisDataIntegrity.FILE_NAME + ".bak-"));
        assertEquals(tampered, Files.readString(result.backup()));
    }

    @Test
    @DisplayName("restoreDefaults creates the file when absent — clean, no backup")
    void restoreDefaultsCreatesFile(@TempDir Path dir) {
        AisDataIntegrity.RestoreResult result = AisDataIntegrity.restoreDefaults(dir);
        assertTrue(result.success());
        assertNull(result.backup());
        assertTrue(Files.exists(dir.resolve(AisDataIntegrity.FILE_NAME)));
        assertTrue(AisDataIntegrity.check(dir).isEmpty());
    }

    @Test
    @DisplayName("Default-content file on disk ⇒ clean")
    void defaultFileIsClean(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(AisDataIntegrity.FILE_NAME),
            "raiseAttributeCaps=true\narmorCapMax=1024.0\narmorToughnessCapMax=1024.0\n");
        assertTrue(AisDataIntegrity.check(dir).isEmpty());
    }
}
