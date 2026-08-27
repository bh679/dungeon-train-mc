package games.brennan.dungeontrain.util;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BundledNbtScanner}'s scan-outcome reporting.
 *
 * <p>The behaviour under test is the distinction {@link BundledNbtScanner.ScanResult#resolved()}
 * draws between "the directory was read and held nothing" and "the directory could not be read".
 * Before that distinction existed every failure path returned a bare empty set, so a loader that
 * could not see the mod's own resources was indistinguishable from a registry that legitimately
 * ships no bundled content — and the carriage-contents registry degraded to generating every
 * carriage empty with nothing in the log to act on.</p>
 *
 * <p>Pure classpath logic: no Minecraft bootstrap needed, matching the other {@code util/} tests.</p>
 */
class BundledNbtScannerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(BundledNbtScannerTest.class);

    /** A resource root the mod really ships — 200+ carriage-interior templates. */
    private static final String CONTENTS_PREFIX = "/data/dungeontrain/contents/";

    @Test
    void scanOfARealPrefixResolvesAndFindsFiles() {
        BundledNbtScanner.ScanResult result =
            BundledNbtScanner.scan(BundledNbtScannerTest.class, CONTENTS_PREFIX, LOGGER);

        assertTrue(result.resolved(), "the bundled contents directory must be readable from the test classpath");
        assertNull(result.failureReason(), "a resolved scan carries no failure reason");
        assertFalse(result.names().isEmpty(), "the bundled contents directory ships .nbt files");
    }

    @Test
    void scanOfAnAbsentPrefixReportsFailureRatherThanEmptiness() {
        BundledNbtScanner.ScanResult result = BundledNbtScanner.scan(
            BundledNbtScannerTest.class, "/data/dungeontrain/definitely-not-a-real-directory/", LOGGER);

        assertFalse(result.resolved(), "an absent prefix is a failure, not an empty directory");
        assertTrue(result.names().isEmpty());
        assertNotNull(result.failureReason(), "a failed scan must say why, so the registry can log it");
    }

    /**
     * The regression this whole change exists to prevent: both outcomes yield an empty name set,
     * so a caller inspecting only the names cannot tell them apart.
     */
    @Test
    void failureAndEmptinessAreDistinguishableOnlyViaResolved() {
        BundledNbtScanner.ScanResult failed = BundledNbtScanner.scan(
            BundledNbtScannerTest.class, "/data/dungeontrain/definitely-not-a-real-directory/", LOGGER);
        // A real directory scanned for an extension none of its files carry: read fine, found none.
        BundledNbtScanner.ScanResult emptyButRead = BundledNbtScanner.scan(
            BundledNbtScannerTest.class, CONTENTS_PREFIX, LOGGER, ".no-such-extension");

        assertTrue(failed.names().isEmpty());
        assertTrue(emptyButRead.names().isEmpty());
        assertFalse(failed.resolved());
        assertTrue(emptyButRead.resolved(), "a readable directory with no matching files is not a failure");
        assertNull(emptyButRead.failureReason());
    }

    @Test
    void extensionFilterSelectsOnlyMatchingFiles() {
        Set<String> nbt = BundledNbtScanner
            .scan(BundledNbtScannerTest.class, CONTENTS_PREFIX, LOGGER, ".nbt").names();
        Set<String> groups = BundledNbtScanner
            .scan(BundledNbtScannerTest.class, CONTENTS_PREFIX, LOGGER, ".group.json").names();

        assertFalse(nbt.isEmpty());
        assertFalse(groups.isEmpty(), "the contents directory ships .group.json sidecars too");
        assertTrue(nbt.size() > groups.size(), "there are far more interiors than group sidecars");
    }

    @Test
    void scanBasenamesStillReturnsTheScannedNames() {
        Set<String> viaWrapper =
            BundledNbtScanner.scanBasenames(BundledNbtScannerTest.class, CONTENTS_PREFIX, LOGGER);
        Set<String> viaScan =
            BundledNbtScanner.scan(BundledNbtScannerTest.class, CONTENTS_PREFIX, LOGGER).names();

        assertEquals(viaScan, viaWrapper, "the legacy wrapper must stay behaviour-compatible");
    }

    /**
     * CarriagePartRegistry derives a grid X-slot from iteration index, so the scan's alphabetical
     * ordering is load-bearing. Guards against a future switch to an unordered immutable set.
     */
    @Test
    void namesIterateAlphabetically() {
        List<String> names = new ArrayList<>(
            BundledNbtScanner.scan(BundledNbtScannerTest.class, CONTENTS_PREFIX, LOGGER).names());

        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        assertEquals(sorted, names);
    }

    @Test
    void namesAreImmutable() {
        Set<String> names =
            BundledNbtScanner.scan(BundledNbtScannerTest.class, CONTENTS_PREFIX, LOGGER).names();
        assertThrows(UnsupportedOperationException.class, () -> names.add("mutated"));
    }
}
