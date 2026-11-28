package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerkGrantsTest {

    private static final PerkDef SHARE = new PerkDef("share-hearts", PerkClass.LOVER, 2, 10);
    private static final PerkDef AURA = new PerkDef("aura", PerkClass.LOVER, 3, 10);
    private static final PerkDef SOON = new PerkDef("coming-soon", PerkClass.LOVER, 3, 10);
    private static final PerkDef TOTEM = new PerkDef("totem", PerkClass.TANK, 2, 10);
    private static final List<PerkDef> ALL = List.of(SHARE, AURA, SOON, TOTEM);
    private static final Set<String> BUILT = Set.of("share-hearts", "aura", "totem");

    private static List<String> ids(PerkGrants.Scope scope) {
        return PerkGrants.covered(scope, ALL, BUILT::contains).stream().map(PerkDef::id).toList();
    }

    @Test
    void everythingCoversBuiltPerksOnly() {
        assertEquals(List.of("share-hearts", "aura", "totem"), ids(new PerkGrants.Everything()));
    }

    @Test
    void onlyCoversOnePerk() {
        assertEquals(List.of("aura"), ids(new PerkGrants.Only("aura")));
        assertTrue(ids(new PerkGrants.Only("coming-soon")).isEmpty());
    }

    @Test
    void classCoversItsBuiltPerks() {
        assertEquals(List.of("share-hearts", "aura"), ids(new PerkGrants.OfClass(PerkClass.LOVER)));
    }

    @Test
    void grantRaisesButNeverLowers() {
        Map<String, Integer> now = Map.of("share-hearts", 5, "aura", 1);
        Map<String, Integer> out = PerkGrants.changes(true, 3, List.of(SHARE, AURA, TOTEM),
            id -> now.getOrDefault(id, 0));
        assertEquals(Map.of("aura", 3, "totem", 3), out);
    }

    @Test
    void grantLevelsAtLeastOne() {
        assertEquals(Map.of("totem", 1), PerkGrants.changes(true, 0, List.of(TOTEM), id -> 0));
    }

    @Test
    void revokeLocksUnlockedPerksOnly() {
        Map<String, Integer> now = Map.of("share-hearts", 4);
        assertEquals(Map.of("share-hearts", 0),
            PerkGrants.changes(false, 7, List.of(SHARE, AURA), id -> now.getOrDefault(id, 0)));
    }

    @Test
    void classOfReadsIdsCaseInsensitively() {
        assertEquals(PerkClass.LOVER, PerkGrants.classOf("Lover").orElseThrow());
        assertTrue(PerkGrants.classOf("wizard").isEmpty());
        assertTrue(PerkGrants.classOf(null).isEmpty());
    }
}
