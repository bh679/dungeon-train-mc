package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which approved translations a build may apply. A translator works against the English they can
 * see, so their fix travels forward to later builds and never back to older ones.
 */
class PoolVersionGateTest {

    @Test
    @DisplayName("the build it was written in may apply it")
    void sameVersionApplies() {
        assertTrue(PoolVersionGate.applies("0.551.15", "0.551.15"));
    }

    @Test
    @DisplayName("a later build may apply it — at any segment")
    void newerClientsApply() {
        assertTrue(PoolVersionGate.applies("0.551.15", "0.551.16"));
        assertTrue(PoolVersionGate.applies("0.551.15", "0.552.0"));
        assertTrue(PoolVersionGate.applies("0.551.15", "1.0.0"));
    }

    @Test
    @DisplayName("an older build may NOT — it shows English the translator never read")
    void olderClientsDoNot() {
        assertFalse(PoolVersionGate.applies("0.551.15", "0.551.14"));
        assertFalse(PoolVersionGate.applies("0.551.15", "0.550.99"));
        assertFalse(PoolVersionGate.applies("1.0.0", "0.999.999"));
    }

    @Test
    @DisplayName("patch is compared, unlike the update checker's deliberately coarser comparison")
    void patchCounts() {
        assertFalse(PoolVersionGate.applies("0.551.15", "0.551.2"));
        assertTrue(PoolVersionGate.applies("0.551.2", "0.551.15"));
    }

    @Test
    @DisplayName("a missing version applies — withholding released work is the worse failure")
    void unknownVersionFailsOpen() {
        assertTrue(PoolVersionGate.applies(null, "0.551.15"));
        assertTrue(PoolVersionGate.applies("", "0.551.15"));
        assertTrue(PoolVersionGate.applies("   ", "0.551.15"));
    }

    @Test
    @DisplayName("a version that will not parse applies, on either side")
    void unparseableFailsOpen() {
        assertTrue(PoolVersionGate.applies("not-a-version", "0.551.15"));
        assertTrue(PoolVersionGate.applies("0.551.15", "dev"));
        assertTrue(PoolVersionGate.applies("0.x.1", "0.551.15"));
    }

    @Test
    @DisplayName("a leading v and a pre-release suffix do not change the ordering")
    void tolerantOfTagShapes() {
        assertTrue(PoolVersionGate.applies("v0.551.15", "0.551.15"));
        assertTrue(PoolVersionGate.applies("0.551.15-beta.1", "0.551.15"));
        assertFalse(PoolVersionGate.applies("v0.552.0", "0.551.15"));
    }

    @Test
    @DisplayName("a missing segment reads as zero")
    void missingSegmentsAreZero() {
        assertTrue(PoolVersionGate.applies("0.551", "0.551.0"));
        assertTrue(PoolVersionGate.applies("0.551", "0.551.9"));
        assertFalse(PoolVersionGate.applies("0.551.1", "0.551"));
    }
}
