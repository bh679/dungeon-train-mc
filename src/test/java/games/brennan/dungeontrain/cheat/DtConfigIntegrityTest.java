package games.brennan.dungeontrain.cheat;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriageGenerationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deviation detection for {@link DtConfigIntegrity}. The check must mirror NeoForge's own load
 * semantics — absent, wrong-typed and out-of-range values are ones NeoForge replaces with the
 * default — so a config that would LOAD as defaults is never flagged. False positives here cost a
 * player their advancements, so the clean cases matter more than the dirty ones.
 */
class DtConfigIntegrityTest {

    private static List<String> server(Object... kv) {
        return DtConfigIntegrity.deviationsOf(map(kv), Map.of());
    }

    private static List<String> common(Object... kv) {
        return DtConfigIntegrity.deviationsOf(Map.of(), map(kv));
    }

    private static Map<String, Object> map(Object... kv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- Clean configs ---------------------------------------------------

    @Test
    @DisplayName("No values at all (missing files) ⇒ defaults ⇒ clean")
    void emptyIsClean() {
        assertTrue(DtConfigIntegrity.deviationsOf(Map.of(), Map.of()).isEmpty());
    }

    @Test
    @DisplayName("Explicit defaults ⇒ clean")
    void explicitDefaultsAreClean() {
        assertTrue(server(
            "train.speed", DungeonTrainConfig.DEFAULT_SPEED,
            "difficulty.carriagesPerTier", DungeonTrainConfig.DEFAULT_CARRIAGES_PER_TIER,
            "difficulty.difficultyEnabled", DungeonTrainConfig.DEFAULT_DIFFICULTY_ENABLED).isEmpty());
    }

    @Test
    @DisplayName("Ints arriving as Long (night-config's TOML integer type) ⇒ clean")
    void longIntegersAreClean() {
        assertTrue(server("difficulty.carriagesPerTier",
            (long) DungeonTrainConfig.DEFAULT_CARRIAGES_PER_TIER).isEmpty());
    }

    @Test
    @DisplayName("A double default written as an int ⇒ clean")
    void integerForDoubleIsClean() {
        assertTrue(server("train.speed", 2L).isEmpty()); // DEFAULT_SPEED is 2.0
    }

    @Test
    @DisplayName("Enum default, any casing ⇒ clean")
    void enumCasingIsClean() {
        assertTrue(server("train.generationMode", "random_grouped").isEmpty());
        assertTrue(server("train.generationMode", DungeonTrainConfig.DEFAULT_GENERATION_MODE).isEmpty());
    }

    @Test
    @DisplayName("Wrong-typed values ⇒ what NeoForge would load ⇒ clean")
    void wrongTypesAreClean() {
        assertTrue(server("difficulty.difficultyEnabled", "yes").isEmpty());
        assertTrue(server("train.speed", "fast").isEmpty());
        assertTrue(server("train.generationMode", 3L).isEmpty());
    }

    @Test
    @DisplayName("Out-of-range values ⇒ NeoForge corrects them to the default ⇒ clean")
    void outOfRangeIsClean() {
        assertTrue(server("train.speed", DungeonTrainConfig.MAX_SPEED + 1).isEmpty());
        assertTrue(server("difficulty.carriagesPerTier", 0L).isEmpty()); // min is 1
        assertTrue(server("train.speed", Double.NaN).isEmpty());
    }

    @Test
    @DisplayName("Ungoverned keys are ignored however far from default they are")
    void ungovernedKeysAreClean() {
        // Performance, privacy and player-content settings must never cost a player their stats.
        assertTrue(server(
            "train.numCarriages", 50L,
            "train.trainY", 200L,
            "train.generateTracks", false,
            "narrative.shareBooksEnabled", false,
            "narrative.deathNotesEnabled", false,
            "discord.worldInfoToRelay", false,
            "intro.introCinematicEnabled", false,
            "carriage.sharedCarriagesEnabled", false).isEmpty());
    }

    @Test
    @DisplayName("difficultyTravelledOffset is NOT governed — it mirrors per-world state")
    void travelledOffsetIsNotGoverned() {
        // DifficultyOffsetLifecycle overwrites this from the world at load, so the file holds the
        // PREVIOUS world's value; governing it would false-positive on every world switch.
        assertTrue(server("difficulty.difficultyTravelledOffset", 5000L).isEmpty());
    }

    // ---- Real deviations -------------------------------------------------

    @Test
    @DisplayName("A changed double is reported with both values")
    void changedDoubleIsReported() {
        assertEquals(List.of("train.speed=5.0 (expected 2.0)"), server("train.speed", 5.0));
    }

    @Test
    @DisplayName("A changed int is reported as an int, not a double")
    void changedIntIsReported() {
        assertEquals(List.of("difficulty.carriagesPerTier=200 (expected 20)"),
            server("difficulty.carriagesPerTier", 200L));
    }

    @Test
    @DisplayName("A flipped flag is reported")
    void changedFlagIsReported() {
        assertEquals(List.of("difficulty.difficultyEnabled=false (expected true)"),
            server("difficulty.difficultyEnabled", false));
    }

    @Test
    @DisplayName("A changed enum is reported by name")
    void changedEnumIsReported() {
        assertEquals(List.of("train.generationMode=LOOPING (expected RANDOM_GROUPED)"),
            server("train.generationMode", CarriageGenerationMode.LOOPING.name()));
    }

    @Test
    @DisplayName("Harder-than-default counts too — the rule is symmetric")
    void harderThanDefaultIsReported() {
        // A shortened onboarding stage makes the game harder, and just as incomparable.
        assertEquals(List.of("difficulty.firstLevelNoHostilesCarriages=0 (expected 10)"),
            server("difficulty.firstLevelNoHostilesCarriages", 0L));
    }

    @Test
    @DisplayName("Common-config keys are checked against the common file, not the server one")
    void commonKeysAreChecked() {
        assertEquals(List.of("spawning.defaultPlayerMobSpawnOneIn=1 (expected 10)"),
            common("spawning.defaultPlayerMobSpawnOneIn", 1L));
        // The same path in the wrong file is nothing — train.* exists in both.
        assertTrue(common("difficulty.carriagesPerTier", 200L).isEmpty());
    }

    @Test
    @DisplayName("Both files' deviations are reported together, server first")
    void bothFilesAreReported() {
        List<String> found = DtConfigIntegrity.deviationsOf(
            map("train.speed", 5.0),
            map("train.defaultBreakBlocksOnContact", false));
        assertEquals(List.of(
            "train.speed=5.0 (expected 2.0)",
            "train.defaultBreakBlocksOnContact=false (expected true)"), found);
    }

    @Test
    @DisplayName("Every governed key is unique and reachable")
    void governedKeysAreWellFormed() {
        long distinct = DtConfigIntegrity.GOVERNED.stream()
            .map(k -> k.file() + "#" + k.path()).distinct().count();
        assertEquals(DtConfigIntegrity.GOVERNED.size(), distinct);
        assertTrue(DtConfigIntegrity.GOVERNED.stream().allMatch(k -> k.path().contains(".")));
    }
}
