package games.brennan.dungeontrain.client;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.RepoPaths;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A photo's caption and the biome checklist name a biome by {@code biome.<ns>.<path>}; a biome no language names
 * would print that key. Pins the readable fallback, and that every biome the band mods add has an English key
 * in the mod's own lang file or in DT's (BetterEnd ships none for two of its biomes, which DT supplies).
 */
class BiomeNamesTest {

    private static final String JARS_PROPERTY = "dungeontrain.test.biomeModJars";
    private static final Path DT_EN_US = RepoPaths.resources().resolve("assets/dungeontrain/lang/en_us.json");
    private static final Pattern BIOME = Pattern.compile("data/([^/]+)/worldgen/biome/(.+)\\.json");
    private static final Pattern LANG = Pattern.compile("assets/[^/]+/lang/en_us\\.json");

    @Test
    void keyFollowsVanillaShape() {
        assertEquals("biome.betterend.flower_islets",
                BiomeNames.key(ResourceLocation.fromNamespaceAndPath("betterend", "flower_islets")));
        assertEquals("biome.mod.cave.lush",
                BiomeNames.key(ResourceLocation.fromNamespaceAndPath("mod", "cave/lush")));
    }

    @Test
    void unnamedBiomeReadsAsItsTidiedId() {
        assertEquals("Flower Islets", BiomeNames.tidy("flower_islets"));
        assertEquals("Waterfall Ponds", BiomeNames.tidy("cave/waterfall_ponds"));
        assertEquals("Megalake", BiomeNames.tidy("megalake"));
        assertEquals("A B", BiomeNames.tidy("a__b_"));
    }

    @Test
    void everyBandModBiomeHasAnEnglishName() throws Exception {
        String property = System.getProperty(JARS_PROPERTY, "");
        assertFalse(property.isEmpty(), JARS_PROPERTY + " is unset — run through Gradle, which points it at the biome mods' jars");
        Set<String> keys = new HashSet<>(langKeys(Files.newInputStream(DT_EN_US)));
        List<String> biomes = new ArrayList<>();
        for (String jar : property.split(File.pathSeparator)) {
            assertFalse(jar.isEmpty(), "a biome mod's jar is missing from the compile classpath: " + property);
            try (ZipFile zip = new ZipFile(jar)) {
                for (ZipEntry entry : zip.stream().toList()) {
                    Matcher biome = BIOME.matcher(entry.getName());
                    if (biome.matches()) {
                        biomes.add(BiomeNames.key(ResourceLocation.fromNamespaceAndPath(biome.group(1), biome.group(2))));
                    } else if (LANG.matcher(entry.getName()).matches()) {
                        keys.addAll(langKeys(zip.getInputStream(entry)));
                    }
                }
            }
        }
        assertTrue(biomes.size() > 100, "expected the band mods' biomes, found " + biomes.size());
        List<String> unnamed = biomes.stream().filter(key -> !keys.contains(key)).sorted().toList();
        assertTrue(unnamed.isEmpty(), "biomes with no English name (add them to DT's lang files, all locales): " + unnamed);
    }

    private static Set<String> langKeys(InputStream in) throws Exception {
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject lang = JsonParser.parseReader(reader).getAsJsonObject();
            return lang.keySet();
        }
    }
}
