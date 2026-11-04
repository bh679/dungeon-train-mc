package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static ModFileFingerprint file(String name, String sha1, List<String> packages, String... ids) {
        return new ModFileFingerprint(name, 100, sha1, 1L, List.of(ids), packages);
    }

    private static final Map<String, Set<String>> KNOWN = Map.of(
        "jei", Set.of("mezz.jei"),
        "alpha", Set.of("dev.alpha"),
        "beta", Set.of("dev.beta"),
        "datapackmod", Set.of());

    @Test
    @DisplayName("Every file without a known hash is reported: suspect, unknown and plausible alike")
    void onlyHashedFilesPass() {
        String good = "a".repeat(40);
        List<ModFileFingerprint> files = List.of(
            file("jei-19.jar", good, List.of("mezz.jei"), "jei"),                          // verified by hash
            file("jei-20-fresh.jar", "b".repeat(40), List.of("mezz.jei", "org.apache"), "jei"), // plausible: fresh release
            file("totally-jei.jar", "c".repeat(40), List.of("net.xray"), "jei"),            // suspect: xray's code
            file("bundle.jar", "d".repeat(40), List.of("dev.alpha"), "alpha", "beta"),      // suspect: beta's code missing
            file("newmod.jar", "e".repeat(40), List.of("a.b"), "newmod"),                   // unknown: no knowledge
            file("resource.jar", "f".repeat(40), List.of(), "datapackmod"));               // plausible: no classes, known []
        assertEquals(List.of("bundle.jar (alpha, beta)", "jei-20-fresh.jar (jei)", "newmod.jar (newmod)",
                "resource.jar (datapackmod)", "totally-jei.jar (jei)"),
            UnapprovedModIntegrity.unverifiedFrom(files, Set.of(good), KNOWN));
        assertEquals(List.of(), UnapprovedModIntegrity.unverifiedFrom(List.of(), Set.of(), Map.of()),
            "no files scanned means nothing unverified — never 'everything is fake'");
    }

    @Test
    @DisplayName("tierOf: verified > suspect (no ids / wrong code) > unknown > plausible, exempt ids ignored")
    void tiers() {
        Set<String> hashes = Set.of("a".repeat(40));
        assertEquals(UnapprovedModIntegrity.Tier.VERIFIED,
            UnapprovedModIntegrity.tierOf(file("x.jar", "a".repeat(40), List.of("net.xray"), "jei"), hashes, KNOWN),
            "an approved hash is verified whatever the packages say");
        assertEquals(UnapprovedModIntegrity.Tier.SUSPECT,
            UnapprovedModIntegrity.tierOf(file("x.jar", "b".repeat(40), List.of("mezz.jei")), hashes, KNOWN), "no ids");
        assertEquals(UnapprovedModIntegrity.Tier.SUSPECT,
            UnapprovedModIntegrity.tierOf(file("x.jar", "b".repeat(40), List.of("net.xray"), "datapackmod"), hashes, KNOWN),
            "classes in a jar claiming a resource-only mod");
        assertEquals(UnapprovedModIntegrity.Tier.UNKNOWN,
            UnapprovedModIntegrity.tierOf(file("x.jar", "b".repeat(40), List.of("mezz.jei"), "jei", "newmod"), hashes, KNOWN),
            "one id without knowledge makes the file unknown, not suspect");
        assertEquals(UnapprovedModIntegrity.Tier.PLAUSIBLE,
            UnapprovedModIntegrity.tierOf(file("x.jar", "b".repeat(40), List.of("mezz.jei"), "jei", "minecraft"), hashes, KNOWN),
            "exempt ids are skipped");
        assertEquals(UnapprovedModIntegrity.Tier.UNKNOWN,
            UnapprovedModIntegrity.tierOf(file("x.jar", "b".repeat(40), List.of(), "jei"), Set.of(), Map.of()),
            "no knowledge at all is unknown");
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
        // here is what keeps the relay kill switch a pure consequence toggle.
        ApprovedModList.setRelayForTest(new ApprovedModList.Payload(Set.of(), Set.of(), false));
        try {
            assertTrue(scan(installed("somemod", "1")).contains("somemod v1"),
                "the kill switch must not blind the scan");
        } finally {
            ApprovedModList.setRelayForTest(null);
        }
    }

    @Test
    @DisplayName("The popup lists unsupported mods — never a blacklisted one, which keeps its own notice")
    void popupExcludesBlacklist() {
        assertTrue(UnapprovedModIntegrity.isUnsupported("somemod", APPROVED, PREFIXES, NO_REVOCATIONS, CHEATS));
        assertTrue(!UnapprovedModIntegrity.isUnsupported("xray", APPROVED, PREFIXES, NO_REVOCATIONS, CHEATS),
            "a known cheat mod is not listed as merely unsupported");
        assertTrue(!UnapprovedModIntegrity.isUnsupported("Sodium", APPROVED, PREFIXES, NO_REVOCATIONS, CHEATS));
        assertTrue(!UnapprovedModIntegrity.isUnsupported("fabric_api_base", APPROVED, PREFIXES, NO_REVOCATIONS, CHEATS));
        assertTrue(UnapprovedModIntegrity.isUnsupported("jade", APPROVED, PREFIXES, Set.of("jade"), CHEATS),
            "a revoked mod is unsupported again");
    }

    @Test
    @DisplayName("The baked list keeps an Essential + Essential Tweaks install, and the recent relay approvals, out of Free Play")
    void bakedListApprovesEssentialTweaksAndRecentApprovals() {
        Map<String, String> installed = installed(
            "essential", "1.5.2", "essentialtweaks", "1.3.0+1.21.1-neoforge",
            "legendarytooltips", "1", "weaponmaster_ydm", "1", "jecharacters", "1",
            "explosiveenhancement", "1", "armor_hud", "1", "shouldersurfing", "1",
            "armor_visibility", "1", "ash_api", "1", "cwb", "1", "cinematic", "1",
            "notapprovedcontrol", "9");
        // The control id proves the scan still reports what the list does not approve.
        assertEquals(List.of("notapprovedcontrol v9"), UnapprovedModIntegrity.unapprovedFrom(
            ApprovedModList.approved(), ApprovedModList.prefixes(), NO_REVOCATIONS, CHEATS, installed));
    }

    @Test
    @DisplayName("Essential is outdated only when its own file is unverified")
    void essentialOutdatedOnlyWhenItsFileIsUnverified() {
        String current = "1".repeat(40);
        ModFileFingerprint latest = file("Essential (neoforge_1.21.1).jar", current, List.of("gg.essential"), "essential");
        ModFileFingerprint old = file("Essential (neoforge_1.21.1).jar", "2".repeat(40), List.of("gg.essential"), "essential");
        ModFileFingerprint jei = file("jei-20.jar", "3".repeat(40), List.of("mezz.jei"), "jei");
        assertFalse(UnapprovedModIntegrity.isOutdatedEssential(latest, Set.of(current), KNOWN), "an approved build is current");
        assertTrue(UnapprovedModIntegrity.isOutdatedEssential(old, Set.of(current), KNOWN), "any other build is outdated");
        assertFalse(UnapprovedModIntegrity.isOutdatedEssential(jei, Set.of(), KNOWN), "another mod's unverified file is not Essential");
        assertFalse(UnapprovedModIntegrity.isOutdatedEssential(null, Set.of(), KNOWN), "not installed");
    }

    @Test
    @DisplayName("A scan still running is pending, never clean; it fails closed")
    void pendingScanIsNotClean() {
        List<String> got = UnapprovedModIntegrity.judgeFiles(false, List.of(), List.of("jei-19.jar"), Set.of(), KNOWN);
        assertEquals(List.of(UnapprovedModIntegrity.PENDING), got);
    }

    @Test
    @DisplayName("A late scan is judged on its files once done: pending clears to clean or to the real findings")
    void lateScanIsJudged() {
        String good = "a".repeat(40);
        List<ModFileFingerprint> files = List.of(file("jei-19.jar", good, List.of("mezz.jei"), "jei"));
        assertEquals(List.of(), UnapprovedModIntegrity.judgeFiles(true, files, List.of("jei-19.jar"), Set.of(good), KNOWN));
        assertEquals(List.of("jei-19.jar (jei)"),
            UnapprovedModIntegrity.judgeFiles(true, files, List.of("jei-19.jar"), Set.of(), KNOWN));
    }

    @Test
    @DisplayName("An expected jar the engine returned no fingerprint for is unverified (could not hash)")
    void missingFileIsUnverified() {
        String good = "a".repeat(40);
        List<ModFileFingerprint> files = List.of(file("jei-19.jar", good, List.of("mezz.jei"), "jei"));
        assertEquals(List.of("huge.jar (could not hash)"),
            UnapprovedModIntegrity.judgeFiles(true, files, List.of("jei-19.jar", "huge.jar"), Set.of(good), KNOWN));
    }

    @Test
    @DisplayName("Expected jars: non-exempt plain .jar outermost files only")
    void expectedJarsSkipExemptAndNonJar() {
        Map<java.nio.file.Path, Boolean> in = new LinkedHashMap<>();
        in.put(java.nio.file.Path.of("mods", "dungeontrain-1.jar"), true);
        in.put(java.nio.file.Path.of("mods", "jei-19.jar"), false);
        in.put(java.nio.file.Path.of("mods", "notes.zip"), false);
        assertEquals(Set.of("jei-19.jar"), UnapprovedModIntegrity.expectedFrom(in));
    }

    @Test
    @DisplayName("Engine tamper findings join the file findings once each")
    void combineAddsTamper() {
        assertEquals(List.of("a.jar (jei)", "x.jar (dtmodverify replaced)"),
            UnapprovedModIntegrity.combine(List.of("a.jar (jei)"), List.of("x.jar (dtmodverify replaced)")));
        assertEquals(List.of("a.jar (jei)"), UnapprovedModIntegrity.combine(List.of("a.jar (jei)"), List.of()));
    }
}
