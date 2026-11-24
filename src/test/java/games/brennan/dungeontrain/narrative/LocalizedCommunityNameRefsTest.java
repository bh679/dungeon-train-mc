package games.brennan.dungeontrain.narrative;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.internal.NameCodec;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AIN's always-on built-in packs (discord, mc_names, dungeontrain, atla, …) add community name pools
 * to its chains, but DT's translated chains REPLACE the merged chain for non-English hosts
 * ({@link AinLocaleOverlay}), so the language-neutral name pools must be carried in each translation
 * at the same segment and weight the English packs use. English-word pools are deliberately left out.
 */
class LocalizedCommunityNameRefsTest {

    private static final Path ROOT = Path.of("src/main/resources/data/dungeontrain/ain_localizations");
    private static final String AIN = "adventureitemnames";

    private record Row(String chain, int segment, String pool, float weight) {}

    private static Row row(String chain, int segment, String pool, float weight) {
        return new Row(chain, segment, pool, weight);
    }

    /** Copied from AIN 0.68.0's built-in packs: every language-neutral name ref, by chain and segment. */
    private static final List<Row> EXPECTED = List.of(
            row("do_you_know_x", 1, "discord_people", 20.0f),
            row("do_you_know_x", 1, "atla_characters", 20.0f),
            row("do_you_know_x", 1, "dungeontrain_first_names", 19.0f),
            row("do_you_know_x", 1, "dungeontrain_last_names", 9.0f),
            row("forged_by", 1, "mc_streamers", 19.0f),
            row("forged_by", 1, "mc_skins", 10.0f),
            row("forged_by", 1, "discord_people", 59.0f),
            row("forged_by", 4, "discord_people", 59.0f),
            row("forged_by", 1, "atla_characters", 34.0f),
            row("forged_by", 4, "atla_characters", 34.0f),
            row("forged_by", 1, "dungeontrain_first_names", 19.0f),
            row("forged_by", 1, "dungeontrain_last_names", 9.0f),
            row("forged_by", 4, "dungeontrain_first_names", 19.0f),
            row("forged_by", 4, "dungeontrain_last_names", 9.0f),
            row("looks_like_something", 1, "mc_streamers", 19.0f),
            row("looks_like_something", 1, "mc_skins", 10.0f),
            row("looks_like_something", 1, "discord_people", 59.0f),
            row("looks_like_something", 1, "atla_characters", 34.0f),
            row("looks_like_something", 1, "dungeontrain_first_names", 19.0f),
            row("looks_like_something", 1, "dungeontrain_last_names", 9.0f),
            row("mob_name", 1, "mc_streamers", 19.0f),
            row("mob_name", 1, "mc_skins", 10.0f),
            row("mob_name", 2, "mc_streamers", 19.0f),
            row("mob_name", 2, "mc_skins", 10.0f),
            row("mob_name", 1, "discord_people", 30.0f),
            row("mob_name", 2, "discord_people", 58.0f),
            row("mob_name", 1, "atla_characters", 34.0f),
            row("mob_name", 2, "atla_characters", 34.0f),
            row("mob_name", 1, "dungeontrain_first_names", 19.0f),
            row("mob_name", 1, "dungeontrain_last_names", 9.0f),
            row("mob_name", 2, "dungeontrain_first_names", 19.0f),
            row("mob_name", 2, "dungeontrain_last_names", 9.0f),
            row("playermob_name", 1, "mc_streamers", 50.0f),
            row("playermob_name", 1, "discord_people", 100.0f),
            row("playermob_name", 3, "fan_suffix", 4.0f),
            row("title_combinations", 1, "mc_streamers", 10.0f),
            row("title_combinations", 1, "mc_skins", 10.0f),
            row("title_combinations", 1, "discord_people", 58.0f),
            row("title_combinations", 1, "atla_characters", 34.0f),
            row("title_combinations", 1, "dungeontrain_first_names", 19.0f),
            row("title_combinations", 1, "dungeontrain_last_names", 9.0f),
            row("weapon_name_full", 1, "mc_streamers", 19.0f),
            row("weapon_name_full", 1, "mc_skins", 10.0f),
            row("weapon_name_full", 1, "discord_people", 58.0f),
            row("weapon_name_full", 1, "atla_characters", 34.0f));

    private static List<Path> locales() throws Exception {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(RepoPaths.root().resolve(ROOT))) {
            dirs.filter(Files::isDirectory).sorted().forEach(out::add);
        }
        return out;
    }

    private static NameChain chain(Path locale, String name) throws Exception {
        try (InputStream in = Files.newInputStream(locale.resolve("chains/" + name + ".json"))) {
            return NameCodec.parseChain(in, ResourceLocation.fromNamespaceAndPath(AIN, name));
        }
    }

    @Test
    @DisplayName("every translated chain carries the community name pools at AIN's segment and weight")
    void communityNameRefsPresent() throws Exception {
        List<Path> locales = locales();
        assertEquals(21, locales.size());
        for (Path locale : locales) {
            for (Row row : EXPECTED) {
                NameSegment seg = chain(locale, row.chain()).segments().get(row.segment());
                ResourceLocation pool = ResourceLocation.fromNamespaceAndPath(AIN, row.pool());
                List<NameSegment.WeightedRef> hits = seg.refs().stream()
                        .filter(r -> pool.equals(r.ref())).toList();
                String where = locale.getFileName() + " " + row.chain() + "/" + row.segment() + " " + row.pool();
                assertEquals(1, hits.size(), where);
                assertEquals(row.weight(), hits.get(0).weight(), 1e-6, where);
            }
        }
    }

    @Test
    @DisplayName("every locale translates the fan tags into a four-entry fan_suffix pool")
    void fanSuffixTranslated() throws Exception {
        for (Path locale : locales()) {
            Path file = locale.resolve("pools/fan_suffix.json");
            assertTrue(Files.isRegularFile(file), file.toString());
            NamePool pool;
            try (InputStream in = Files.newInputStream(file)) {
                pool = NameCodec.parsePool(in, ResourceLocation.fromNamespaceAndPath(AIN, "fan_suffix"));
            }
            assertEquals(ResourceLocation.fromNamespaceAndPath(AIN, "fan_suffix"), pool.id(), file.toString());
            assertEquals(4, pool.entries().size(), file.toString());
            for (NamePool.PoolEntry entry : pool.entries()) {
                assertFalse(entry.text().isBlank(), file.toString());
                assertFalse(entry.text().contains("—") || entry.text().contains("–"), file.toString());
            }
        }
    }
}
