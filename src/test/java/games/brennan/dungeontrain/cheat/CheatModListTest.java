package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation, baked ∪ relay merge, and JSON round-trip for {@link CheatModList}. No live
 * {@code ModList} / config dir needed — the relay overlay is injected via the test seam.
 */
class CheatModListTest {

    @AfterEach
    void reset() {
        CheatModList.setRelayForTest(null);
    }

    @Test
    @DisplayName("sanitize lowercases, trims, and drops malformed IDs")
    void sanitizeDropsJunk() {
        Set<String> clean = CheatModList.sanitize(List.of(
            "XRay",            // uppercased -> xray
            "  freecam  ",     // padded -> freecam
            "ok_mod-1.2",      // valid charset
            "bad id!",         // space + '!' -> dropped
            "",                // empty -> dropped
            "with space"));    // dropped

        assertTrue(clean.contains("xray"));
        assertTrue(clean.contains("freecam"));
        assertTrue(clean.contains("ok_mod-1.2"));
        assertFalse(clean.contains("bad id!"));
        assertEquals(3, clean.size());
    }

    @Test
    @DisplayName("isValidModId accepts [a-z0-9_.-], rejects spaces/symbols/overlong")
    void validModId() {
        assertTrue(CheatModList.isValidModId("xray"));
        assertTrue(CheatModList.isValidModId("Some-Mod_1.2"));   // lowercased internally
        assertFalse(CheatModList.isValidModId("bad id"));
        assertFalse(CheatModList.isValidModId("nope!"));
        assertFalse(CheatModList.isValidModId(""));
        assertFalse(CheatModList.isValidModId(null));
        assertFalse(CheatModList.isValidModId("x".repeat(65)));
    }

    @Test
    @DisplayName("effective() merges baked with the relay overlay")
    void effectiveMerges() {
        // A baked entry is always present.
        assertTrue(CheatModList.effective().contains("xray"));

        CheatModList.setRelayForTest(List.of("mycustomcheat"));
        Set<String> eff = CheatModList.effective();
        assertTrue(eff.contains("mycustomcheat"), "relay id present");
        assertTrue(eff.contains("xray"), "baked id still present");
    }

    @Test
    @DisplayName("curated baked IDs are present in effective()")
    void bakedCurationPresent() {
        Set<String> eff = CheatModList.effective();
        assertTrue(eff.contains("clientcommands"), "clientcommands is blacklisted");
        assertTrue(eff.contains("baritone"), "baritone is blacklisted");
        assertTrue(eff.contains("veinminer"), "veinminer is blacklisted");
        assertTrue(eff.contains("ftbultimine"), "ftbultimine is blacklisted");
        assertTrue(eff.contains("oreexcavation"), "oreexcavation is blacklisted");
        assertTrue(eff.contains("veinmining"), "veinmining is blacklisted");
    }

    @Test
    @DisplayName("trade-limit removers and item editors are baked")
    void tradeAndItemEditorCurationPresent() {
        Set<String> eff = CheatModList.effective();
        // Unlimited villager trading — an uncapped emerald faucet.
        assertTrue(eff.contains("infinitetrading"), "infinitetrading is blacklisted");
        assertTrue(eff.contains("mr_unlimited_trading"), "mr_unlimited_trading is blacklisted");
        assertTrue(eff.contains("mr_unlimited_villagertrades"),
            "mr_unlimited_villagertrades is blacklisted");
        // In-game item / NBT editors — /give with a GUI, and no command to classify.
        assertTrue(eff.contains("infinityeditor"), "infinityeditor is blacklisted");
        assertTrue(eff.contains("infinity_item_editor_re"),
            "infinity_item_editor_re is blacklisted");
        assertTrue(eff.contains("cadeditor"), "cadeditor is blacklisted");
        assertTrue(eff.contains("dine"), "dine is blacklisted");
    }

    @Test
    @DisplayName("honest tools are NOT baked — they gate on operator permission instead")
    void honestToolsNotBaked() {
        // These three are the reason OperatorIntegrity exists. REI and JEI are recipe viewers most
        // players run honestly, and WorldEdit must only cost you the run when it is USED. All three
        // need permission level 2 to do anything, which OperatorIntegrity catches on its own —
        // listing them here would flag a clean install and punish the wrong people.
        for (String honest : List.of("roughlyenoughitems", "jei", "worldedit",
                "fastasyncworldedit", "emi")) {
            assertFalse(CheatModList.BAKED.contains(honest),
                honest + " must gate on operator permission, not on being installed");
        }
    }

    @Test
    @DisplayName("no library / loader ID leaked into the baked list")
    void noLibraryIdsBaked() {
        // The vein-miner IDs were harvested from mods.toml files, where a mod's own modId sits
        // beside its dependencies'. A dependency ID slipping in would flip Free Play on for every
        // install of an innocent mod that depends on it — the one way this list can do damage.
        for (String lib : List.of("minecraft", "neoforge", "forge", "collective", "architectury",
                "tconstruct", "kotlinforforge", "midnightlib", "rickcore", "create", "amber",
                "konfig", "shouldersurfing")) {
            assertFalse(CheatModList.BAKED.contains(lib), lib + " is a dependency, not a cheat mod");
        }
    }

    @Test
    @DisplayName("toJson -> parse round-trips a mod-ID set")
    void jsonRoundTrip() {
        Set<String> ids = Set.of("xray", "freecam", "baritone");
        Set<String> back = CheatModList.parse(CheatModList.toJson(ids));
        assertEquals(ids, back);
    }

    @Test
    @DisplayName("parse tolerates malformed bodies and drops junk entries")
    void parseIsDefensive() {
        assertTrue(CheatModList.parse("not json").isEmpty());
        assertTrue(CheatModList.parse("{}").isEmpty());
        assertTrue(CheatModList.parse("{\"mods\":\"nope\"}").isEmpty());
        assertEquals(Set.of("xray"),
            CheatModList.parse("{\"ok\":true,\"mods\":[\"XRay\",\"bad id!\",123]}"));
    }
}
