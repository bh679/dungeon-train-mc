package games.brennan.dungeontrain.cheat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whitelist's parsing, set algebra and baked-resource sanity. Everything here is either pure or
 * driven through {@link ApprovedModList#setRelayForTest}, so no network, disk or live mod list is
 * involved.
 */
class ApprovedModListTest {

    @AfterEach
    void reset() {
        ApprovedModList.setRelayForTest(null);
    }

    // ---- the served payload ---------------------------------------------------------------------

    @Test
    @DisplayName("parse reads approved, revoked and enforce; junk IDs are dropped")
    void parsesPayload() {
        ApprovedModList.Payload p = ApprovedModList.parse(
            "{\"ok\":true,\"approved\":[\"Sodium\",\"bad id\",\"jade\"],"
                + "\"revoked\":[\"xray\"],\"enforce\":true}");
        assertEquals(Set.of("sodium", "jade"), p.approved());
        assertEquals(Set.of("xray"), p.revoked());
        assertTrue(p.enforce());
    }

    @Test
    @DisplayName("An unreadable body parses to null, so the caller keeps what it had")
    void malformedBodyIsNull() {
        for (String body : new String[]{"", "not json", "[]", "\"str\""}) {
            assertNull(ApprovedModList.parse(body), body);
        }
        // An object with a junk list is still readable — just empty on that list.
        assertEquals(Set.of(), ApprovedModList.parse("{\"approved\":\"nope\"}").approved());
    }

    @Test
    @DisplayName("enforce is null (unsaid) when the field is missing or not a boolean")
    void enforceUnsaidWhenMissing() {
        assertNull(ApprovedModList.parse("{\"approved\":[\"jade\"]}").enforce());
        assertNull(ApprovedModList.parse("{\"enforce\":\"true\"}").enforce());
        assertEquals(Boolean.TRUE, ApprovedModList.parse("{\"enforce\":true}").enforce());
        assertEquals(Boolean.FALSE, ApprovedModList.parse("{\"enforce\":false}").enforce());
    }

    @Test
    @DisplayName("toJson round-trips through parse")
    void jsonRoundTrips() {
        ApprovedModList.Payload p =
            new ApprovedModList.Payload(Set.of("jade", "sodium"), Set.of("xray"), true);
        ApprovedModList.Payload back = ApprovedModList.parse(ApprovedModList.toJson(p));
        assertEquals(p.approved(), back.approved());
        assertEquals(p.revoked(), back.revoked());
        assertTrue(back.enforce());
    }

    // ---- the set algebra ------------------------------------------------------------------------

    @Test
    @DisplayName("The effective set is (baked u approved) - revoked")
    void effectiveSetAlgebra() {
        ApprovedModList.setRelayForTest(
            new ApprovedModList.Payload(Set.of("somenewmod"), Set.of("sodium"), false));
        Set<String> eff = ApprovedModList.approved();
        assertTrue(eff.contains("somenewmod"), "a relay approval is added");
        assertTrue(eff.contains("dungeontrain"), "the baked list is still there");
        assertFalse(eff.contains("sodium"), "a relay revocation beats the baked approval");
    }

    @Test
    @DisplayName("A revocation beats an approval and beats a prefix match")
    void revocationWins() {
        Set<String> approved = Set.of("jade", "fabric_api_base");
        List<String> prefixes = List.of("fabric_");
        Set<String> revoked = Set.of("jade", "fabric_api_base");
        assertFalse(ApprovedModList.isApproved("jade", approved, prefixes, revoked));
        assertFalse(ApprovedModList.isApproved("fabric_api_base", approved, prefixes, revoked));
    }

    @Test
    @DisplayName("The prefix rule approves a family without enumerating it")
    void prefixApproves() {
        assertTrue(ApprovedModList.isApproved(
            "fabric_renderer_api_v1", Set.of(), List.of("fabric_"), Set.of()));
        // Anchored on the underscore: a mod merely STARTING with "fabric" is untouched.
        assertFalse(ApprovedModList.isApproved(
            "fabricfurniture", Set.of(), List.of("fabric_"), Set.of()));
    }

    @Test
    @DisplayName("Matching is case-insensitive on the mod ID")
    void matchIsCaseInsensitive() {
        assertTrue(ApprovedModList.isApproved("Sodium", Set.of("sodium"), List.of(), Set.of()));
    }

    @Test
    @DisplayName("An empty or null mod id is never approved")
    void emptyIdNeverApproved() {
        assertFalse(ApprovedModList.isApproved(null, Set.of(), List.of("fabric_"), Set.of()));
        assertFalse(ApprovedModList.isApproved("  ", Set.of(), List.of("fabric_"), Set.of()));
    }

    @Test
    @DisplayName("Enforcement is baked ON; only an explicit relay value changes it")
    void enforcementIsBakedOn() {
        assertTrue(ApprovedModList.BAKED_ENFORCE);
        ApprovedModList.setRelayForTest(new ApprovedModList.Payload(Set.of(), Set.of(), null));
        assertTrue(ApprovedModList.enforce(), "a relay that says nothing leaves the baked ON");
        ApprovedModList.setRelayForTest(new ApprovedModList.Payload(Set.of(), Set.of(), false));
        assertFalse(ApprovedModList.enforce(), "the relay's explicit false is the kill switch");
        ApprovedModList.setRelayForTest(new ApprovedModList.Payload(Set.of(), Set.of(), true));
        assertTrue(ApprovedModList.enforce());
    }

    @Test
    @DisplayName("A payload without enforce doesn't undo an earlier explicit kill switch")
    void unsaidEnforceKeepsCurrentValue() {
        ApprovedModList.setRelayForTest(new ApprovedModList.Payload(Set.of(), Set.of(), false));
        ApprovedModList.accept(new ApprovedModList.Payload(Set.of("jade"), Set.of(), null));
        assertFalse(ApprovedModList.enforce());
    }

    @Test
    @DisplayName("toJson omits an unsaid enforce, so the cache can't invent one")
    void toJsonOmitsUnsaidEnforce() {
        String json = ApprovedModList.toJson(new ApprovedModList.Payload(Set.of(), Set.of(), null));
        assertFalse(json.contains("enforce"), json);
    }

    // ---- the baked resource ---------------------------------------------------------------------

    @Test
    @DisplayName("The baked resource loads, and covers the mods every player has")
    void bakedResourceCoversOurOwnStack() {
        Set<String> baked = ApprovedModList.approved();
        for (String id : List.of("minecraft", "neoforge", "dungeontrain", "sable", "sablecompanion",
            "veil", "adventureitemnames", "adventureitemstats", "playermob",
            "enderchestpersistence", "tradeeverything", "discordpresence", "ediblebackpacks")) {
            assertTrue(baked.contains(id), id + " must be approved — every player runs it");
        }
    }

    @Test
    @DisplayName("The baked resource approves the whole modpack roster")
    void bakedResourceCoversTheModpack() {
        Set<String> baked = ApprovedModList.approved();
        // Real modIds, not store slugs: Item Highlighter ships as `highlighter`, Iris as `iris`.
        for (String id : List.of("sodium", "iris", "jade", "appleskin", "ferritecore", "modernfix",
            "highlighter", "khi", "sablejade", "distanthorizons", "mousetweaks", "jei")) {
            assertTrue(baked.contains(id), id + " is in the modpack, so it must be approved");
        }
    }

    @Test
    @DisplayName("The fabric_ prefix rule is baked, so Connector's ~45 modules don't free-play anyone")
    void bakedPrefixesIncludeFabric() {
        assertTrue(ApprovedModList.prefixes().contains("fabric_"));
        assertTrue(ApprovedModList.isApproved("fabric_api_base"));
    }

    @Test
    @DisplayName("No baked ID is malformed — a typo here un-approves a real mod silently")
    void bakedIdsAreValid() {
        for (String id : ApprovedModList.approved()) {
            assertTrue(ModIds.isValid(id), id + " is not a plausible mod id");
        }
    }

    @Test
    @DisplayName("The whitelist and the cheat-mod blacklist are disjoint")
    void whitelistAndBlacklistDoNotOverlap() {
        for (String id : ApprovedModList.approved()) {
            assertFalse(CheatModList.BAKED.contains(id),
                id + " is on BOTH the approved list and the cheat-mod list — pick one");
        }
    }

    @Test
    @DisplayName("Every group in the resource carries a `why`, so curation stays reviewable")
    void everyGroupExplainsItself() throws Exception {
        try (var in = ApprovedModList.class.getResourceAsStream(ApprovedModList.RESOURCE)) {
            assertNotNull(in, "the baked resource must ship in the jar");
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject groups = root.getAsJsonObject("groups");
            assertFalse(groups.isEmpty(), "the resource must have groups");
            for (var e : groups.entrySet()) {
                assertTrue(e.getValue().getAsJsonObject().has("why"),
                    "group " + e.getKey() + " must say why its mods are approved");
            }
        }
    }
}
