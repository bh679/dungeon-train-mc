package games.brennan.dungeontrain.modrec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Membership rules for the Mod Recommendations roster. Pure — the roster is injected, so these run
 * without a built jar or a Minecraft bootstrap.
 */
class ModRosterTest {

    private static final ModRoster.Roster ROSTER = new ModRoster.Roster(Set.of(
            "dungeontrain", "sable", "playermob", "appleskin", "minecraft", "neoforge",
            "distanthorizons", "interactiveplayermobs"), List.of("fabric_"));

    private static ModRoster.LoadedMod mod(String id, String name) {
        return new ModRoster.LoadedMod(id, name);
    }

    @Test
    @DisplayName("our own mods, our companions and the platform are never offered")
    void ourMods() {
        List<ModRoster.LoadedMod> loaded = List.of(
                mod("dungeontrain", "Dungeon Train"),
                mod("sable", "Sable"),
                mod("appleskin", "AppleSkin"),
                mod("minecraft", "Minecraft"),
                mod("neoforge", "NeoForge"));
        assertTrue(ModRoster.leftovers(ROSTER, loaded).isEmpty());
    }

    @Test
    @DisplayName("a mod the player added themselves is offered, in the order given")
    void leftoversKeepOrder() {
        List<ModRoster.LoadedMod> loaded = List.of(
                mod("dungeontrain", "Dungeon Train"),
                mod("jei", "Just Enough Items"),
                mod("appleskin", "AppleSkin"),
                mod("xaerominimap", "Xaero's Minimap"));
        List<ModRoster.LoadedMod> out = ModRoster.leftovers(ROSTER, loaded);
        assertEquals(List.of("jei", "xaerominimap"), out.stream().map(ModRoster.LoadedMod::modId).toList());
    }

    @Test
    @DisplayName("a companion is matched by display name when its modId differs from its slug")
    void matchedByDisplayName() {
        // The modpack config knows this one as the slug "interactive-player-mobs"; the loader
        // reports modId "playermob". Either key alone would be enough here, but a companion that
        // only matches on its NAME must still be excluded.
        assertTrue(ModRoster.leftovers(ROSTER, List.of(
                mod("somethingelse", "Interactive Player Mobs"))).isEmpty());
    }

    @Test
    @DisplayName("matching ignores case, spaces, hyphens and underscores")
    void normalisation() {
        assertEquals("distanthorizons", ModRoster.normalise("Distant-Horizons"));
        assertEquals("distanthorizons", ModRoster.normalise("Distant Horizons"));
        assertEquals("distanthorizons", ModRoster.normalise("distant_horizons"));
        assertTrue(ModRoster.isOurs(ROSTER, "distant_horizons", "Distant Horizons"));
        assertFalse(ModRoster.isOurs(ROSTER, "jei", "Just Enough Items"));
    }

    @Test
    @DisplayName("the Forgified Fabric API's fabric_* modules are excluded by prefix, not enumerated")
    void fabricApiFamilyExcluded() {
        // Sinytra Connector registers ~44 of these and ~92% of players load them; enumerating the
        // set would go stale. Matched on the RAW id, so only the underscore namespace is caught.
        List<ModRoster.LoadedMod> loaded = List.of(
                mod("fabric_api_base", "Fabric API Base"),
                mod("fabric_renderer_api_v1", "Fabric Renderer API (v1)"),
                mod("fabric_networking_api_v1", "Fabric Networking API (v1)"),
                mod("jei", "Just Enough Items"));
        assertEquals(List.of("jei"), ModRoster.leftovers(ROSTER, loaded).stream()
                .map(ModRoster.LoadedMod::modId).toList());
    }

    @Test
    @DisplayName("a prefix match must not swallow an unrelated mod that merely starts with the word")
    void prefixDoesNotOvermatch() {
        // "fabricfurniture" normalises past the key set and does NOT start with "fabric_", so a real
        // third-party mod keeps its tile.
        assertFalse(ModRoster.isOurs(ROSTER, "fabricfurniture", "Fabric Furniture"));
    }

    @Test
    @DisplayName("an unreadable roster fails CLOSED — no leftovers, so the page never opens")
    void emptyRosterOffersNothing() {
        List<ModRoster.LoadedMod> loaded = List.of(
                mod("dungeontrain", "Dungeon Train"), mod("jei", "Just Enough Items"));
        assertTrue(ModRoster.leftovers(ModRoster.Roster.of(Set.of()), loaded).isEmpty());
    }

    @Test
    @DisplayName("blank and null mod ids are skipped rather than offered as tiles")
    void skipsJunk() {
        List<ModRoster.LoadedMod> loaded = java.util.Arrays.asList(
                mod("", "Nameless"), mod(null, "Null id"), null, mod("jei", "Just Enough Items"));
        assertEquals(List.of("jei"), ModRoster.leftovers(ROSTER, loaded).stream()
                .map(ModRoster.LoadedMod::modId).toList());
    }
}
