package games.brennan.dungeontrain.narrative;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.internal.NameCodec;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DT's translated {@code playermob_name} chains REPLACE AIN's English one for non-English hosts
 * ({@link AinLocaleOverlay}), so they must carry the same per-segment rules AIN 0.68.0 ships: no fan
 * tag or number suffix on a titled or surnamed name, and supporters usually tagged. Parsed through
 * AIN's own codec, so a field AIN can't read fails here rather than silently in-game.
 */
class LocalizedPlayerMobChainRulesTest {

    private static final Path ROOT = Path.of("src/main/resources/data/dungeontrain/ain_localizations");
    private static final ResourceLocation FALLBACK =
            ResourceLocation.fromNamespaceAndPath("adventureitemnames", "playermob_name");
    private static final List<Integer> SKIP_IF = List.of(0, 2, 5);
    private static final List<ResourceLocation> SUPPORTER_REFS = List.of(
            ResourceLocation.fromNamespaceAndPath("adventureitemnames", "discord_people"),
            ResourceLocation.fromNamespaceAndPath("dungeontrain", "backers"));
    private static final int FAN = 3;
    private static final int SUFFIX = 4;

    private static List<Path> chainFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> locales = Files.list(RepoPaths.root().resolve(ROOT))) {
            locales.sorted().forEach(dir -> {
                Path chain = dir.resolve("chains/playermob_name.json");
                if (Files.isRegularFile(chain)) out.add(chain);
            });
        }
        return out;
    }

    private static NameChain parse(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return NameCodec.parseChain(in, FALLBACK);
        }
    }

    @Test
    @DisplayName("every locale ships a playermob_name chain")
    void everyLocaleHasAChain() throws IOException {
        assertEquals(21, chainFiles().size());
    }

    @Test
    @DisplayName("Fan and Suffix skip on titled/surnamed names and favour supporters, in every locale")
    void fanAndSuffixCarryAinRules() throws Exception {
        for (Path file : chainFiles()) {
            List<NameSegment> segs = parse(file).segments();
            assertEquals(6, segs.size(), file.toString());

            for (int i : new int[] {FAN, SUFFIX}) {
                assertEquals(SKIP_IF, segs.get(i).skipIf(), file + " segment " + i);
            }

            List<NameSegment.ChanceRule> fan = segs.get(FAN).chanceIf();
            assertEquals(1, fan.size(), file + " fan");
            assertEquals(0.5f, fan.get(0).chance(), 1e-6, file + " fan");
            assertSupporterCondition(fan.get(0).when().get(0), file);

            List<NameSegment.ChanceRule> suffix = segs.get(SUFFIX).chanceIf();
            assertEquals(2, suffix.size(), file + " suffix");
            assertEquals(0.04f, suffix.get(0).chance(), 1e-6, file + " suffix both");
            assertEquals(2, suffix.get(0).when().size(), file + " suffix both");
            assertSupporterCondition(suffix.get(0).when().get(0), file);
            assertEquals(FAN, suffix.get(0).when().get(1).segment(), file + " suffix both");
            assertTrue(suffix.get(0).when().get(1).refs().isEmpty(), file + " suffix both");
            assertEquals(0.8f, suffix.get(1).chance(), 1e-6, file + " suffix");
            assertSupporterCondition(suffix.get(1).when().get(0), file);

            for (int i : new int[] {0, 1, 2, 5}) {
                assertTrue(segs.get(i).skipIf().isEmpty(), file + " segment " + i);
                assertTrue(segs.get(i).chanceIf().isEmpty(), file + " segment " + i);
            }
        }
    }

    private static void assertSupporterCondition(NameSegment.Condition c, Path file) {
        assertEquals(1, c.segment(), file + " condition segment");
        assertEquals(SUPPORTER_REFS, c.refs(), file + " condition refs");
    }
}
