package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure rule of {@link EngineIntegrity#tamperedFrom} — is the verification engine DT's embedded one? */
class EngineIntegrityTest {

    private static final Path DT = Path.of("mods", "dungeontrain-0.1644.0.jar");
    private static final String SHA = "a".repeat(40);
    private static final String VER = "0.1.1";

    private static EngineIntegrity.FileClaim claim(Path p, String... ids) {
        return new EngineIntegrity.FileClaim(p, Set.of(ids));
    }

    private static final List<EngineIntegrity.FileClaim> STOCK = List.of(
        claim(DT, "dungeontrain"), claim(DT, "dtmodverify"),
        claim(Path.of("mods", "neoforge.jar"), "neoforge"), claim(Path.of("mods", "jei.jar"), "jei"));

    @Test
    @DisplayName("The engine nested in DT's jar, with the baked hash and version, is clean")
    void embeddedEngineIsClean() {
        assertEquals(List.of(), EngineIntegrity.tamperedFrom(DT, DT, SHA, SHA, VER, VER, STOCK));
    }

    @Test
    @DisplayName("An engine whose outermost file is not DT's jar was replaced")
    void topLevelEngineIsReplaced() {
        Path fake = Path.of("mods", "fake.jar");
        List<EngineIntegrity.FileClaim> claims = List.of(claim(DT, "dungeontrain"), claim(fake, "dtmodverify", "xray"));
        assertEquals(List.of("fake.jar (dtmodverify replaced)"),
            EngineIntegrity.tamperedFrom(DT, fake, SHA, SHA, VER, VER, claims));
    }

    @Test
    @DisplayName("A nested engine with another hash or version is not DT's build")
    void hashOrVersionMismatch() {
        assertEquals(List.of("dtmodverify (engine modified)"),
            EngineIntegrity.tamperedFrom(DT, DT, "b".repeat(40), SHA, VER, VER, STOCK));
        assertEquals(List.of("dtmodverify v9.9.9 (engine replaced)"),
            EngineIntegrity.tamperedFrom(DT, DT, SHA, SHA, "9.9.9", VER, STOCK));
        assertEquals(List.of(), EngineIntegrity.tamperedFrom(DT, DT, "b".repeat(40), "", "9.9.9", "", STOCK),
            "nothing baked: nothing to compare");
    }

    @Test
    @DisplayName("Another file claiming dungeontrain or forge is flagged; the loader's own forge id is not")
    void otherFilesClaimingGuardedIds() {
        List<EngineIntegrity.FileClaim> claims = List.of(claim(DT, "dungeontrain", "dtmodverify"),
            claim(Path.of("mods", "a.jar"), "dungeontrain"), claim(Path.of("mods", "b.jar"), "forge", "xray"),
            claim(Path.of("libs", "neoforge.jar"), "neoforge", "forge"));
        assertEquals(List.of("a.jar (claims dungeontrain)", "b.jar (claims forge)"),
            EngineIntegrity.tamperedFrom(DT, DT, SHA, SHA, VER, VER, claims));
    }
}
