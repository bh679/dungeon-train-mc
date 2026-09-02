package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure classification for
 * {@link UnapprovedModIntegrity#unapprovedFrom(Set, List, Set, Set, Map)} — which installed mods
 * are NOT approved, as {@code "<modId> v<version>"} display strings. No live {@code ModList} needed.
 */
class UnapprovedModIntegrityTest {

    private static final Set<String> APPROVED = Set.of("dungeontrain", "sodium", "jade");
    private static final List<String> PREFIXES = List.of("fabric_");
    private static final Set<String> NO_REVOCATIONS = Set.of();
    private static final Set<String> CHEATS = Set.of("xray", "baritone");

    private static Map<String, String> installed(String... idVersionPairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < idVersionPairs.length; i += 2) {
            out.put(idVersionPairs[i], idVersionPairs[i + 1]);
        }
        return out;
    }

    private static List<String> scan(Map<String, String> installed) {
        return UnapprovedModIntegrity.unapprovedFrom(
            APPROVED, PREFIXES, NO_REVOCATIONS, CHEATS, installed);
    }

    @Test
    @DisplayName("An all-approved mod list is clean")
    void approvedListIsClean() {
        assertEquals(List.of(), scan(installed("dungeontrain", "1.0", "sodium", "0.6", "jade", "15")));
    }

    @Test
    @DisplayName("An unapproved mod is reported with its version")
    void reportsUnapprovedWithVersion() {
        assertEquals(List.of("somemod v2.3.4"),
            scan(installed("dungeontrain", "1.0", "somemod", "2.3.4")));
    }

    @Test
    @DisplayName("Results are sorted, so the login notice reads the same every boot")
    void resultsAreSorted() {
        assertEquals(List.of("aaa v1", "zzz v1"), scan(installed("zzz", "1", "aaa", "1")));
    }

    @Test
    @DisplayName("Matching is case-insensitive on the mod ID, but the display keeps the real ID")
    void matchIsCaseInsensitive() {
        assertEquals(List.of(), scan(installed("SODIUM", "0.6")));
        assertEquals(List.of("SomeMod v1"), scan(installed("SomeMod", "1")));
    }

    @Test
    @DisplayName("A prefix-matched module is approved without being enumerated")
    void prefixMatchedModuleIsApproved() {
        assertEquals(List.of(), scan(installed("fabric_api_base", "0.1", "fabric_renderer_api_v1", "0.1")));
    }

    @Test
    @DisplayName("A known cheat mod is left to CheatModIntegrity, not named twice")
    void cheatModsAreNotReportedHere() {
        // xray is unapproved AND blacklisted; the specific cheat-mod notice is the useful one.
        assertEquals(List.of("somemod v1"), scan(installed("xray", "1.2", "somemod", "1")));
    }

    @Test
    @DisplayName("A revoked mod is unapproved even though the approved set still lists it")
    void revocationMakesAnApprovedModUnapproved() {
        List<String> found = UnapprovedModIntegrity.unapprovedFrom(
            APPROVED, PREFIXES, Set.of("sodium"), CHEATS, installed("sodium", "0.6"));
        assertEquals(List.of("sodium v0.6"), found);
    }

    @Test
    @DisplayName("A revoked prefix-matched module is unapproved too")
    void revocationBeatsThePrefixRule() {
        List<String> found = UnapprovedModIntegrity.unapprovedFrom(
            APPROVED, PREFIXES, Set.of("fabric_api_base"), CHEATS, installed("fabric_api_base", "0.1"));
        assertEquals(List.of("fabric_api_base v0.1"), found);
    }

    @Test
    @DisplayName("An empty installed list is clean rather than an error")
    void emptyInstalledListIsClean() {
        assertEquals(List.of(), scan(installed()));
    }

    @Test
    @DisplayName("Detection runs whether or not enforcement is on — only the consequence is gated")
    void detectionIsIndependentOfEnforcement() {
        // The scan itself never consults the switch; isSessionFreePlay does. Asserting the split
        // here is what stops a future refactor from making the observe-only period silent.
        assertTrue(scan(installed("somemod", "1")).contains("somemod v1"));
        ApprovedModList.setRelayForTest(ApprovedModList.Payload.EMPTY);
        try {
            assertTrue(!ApprovedModList.enforce(),
                "enforcement must default off so detection alone costs a player nothing");
        } finally {
            ApprovedModList.setRelayForTest(null);
        }
    }
}
