package games.brennan.dungeontrain.compat.mixinguard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ThirdPartyMixinTargets} to the library jars DT is built against. If a WorldWeaver / BCLib /
 * BetterEnd / TerraBlender bump moves something a mixin hooks, the first test fails here rather than the
 * mixin silently skipping itself (and the feature falling back) for every player.
 */
final class ThirdPartyMixinTargetsTest {

    private static final List<String> GUARDED_CONFIGS = List.of(
            "dungeontrain.betterend.mixins.json", "dungeontrain.terrablender.mixins.json");
    private static final String PLUGIN = "games.brennan.dungeontrain.mixin.ThirdPartyMixinPlugin";
    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";

    @Test
    void pinnedLibrariesHaveEverythingEachGuardedMixinHooks() throws IOException {
        List<String> misses = new ArrayList<>();
        for (String mixin : new TreeSet<>(ThirdPartyMixinTargets.guardedMixins())) {
            ThirdPartyMixinTargets.Spec spec = ThirdPartyMixinTargets.forMixin(mixin);
            for (Map.Entry<String, List<MixinTargetRequirement>> e : spec.allChecked().entrySet()) {
                ClassNode node = readClass(e.getKey());
                if (node == null) {
                    misses.add(mixin + " → " + e.getKey() + " (class not on the classpath)");
                    continue;
                }
                MixinTargetCheck.missing(node, e.getValue())
                        .forEach(m -> misses.add(mixin + " → " + e.getKey() + ": " + m));
            }
        }
        assertTrue(misses.isEmpty(), "Pinned libraries no longer match the guarded mixins:\n  "
                + String.join("\n  ", misses));
    }

    @Test
    void everyMixinInTheGuardedConfigsHasASpec() throws IOException {
        Set<String> listed = new TreeSet<>();
        for (String config : GUARDED_CONFIGS) {
            JsonObject json = readConfig(config);
            assertEquals(PLUGIN, json.get("plugin").getAsString(), config + " must use " + PLUGIN);
            assertEquals(0, json.getAsJsonObject("injectors").get("defaultRequire").getAsInt(),
                    config + " must not hard-require injections; the plugin decides");
            String pkg = json.get("package").getAsString() + ".";
            for (String side : List.of("mixins", "client", "server")) {
                JsonArray names = json.getAsJsonArray(side);
                if (names == null) continue;
                for (JsonElement name : names) listed.add(pkg + name.getAsString());
            }
        }
        assertEquals(listed, new TreeSet<>(ThirdPartyMixinTargets.guardedMixins()),
                "ThirdPartyMixinTargets must list exactly the mixins in the guarded configs");
    }

    @Test
    void specTargetsMatchEachMixinsOwnAnnotation() throws IOException {
        for (String mixin : ThirdPartyMixinTargets.guardedMixins()) {
            ClassNode node = readClass(mixin);
            assertNotNull(node, "compiled mixin class not found: " + mixin);
            assertEquals(annotatedTargets(node), new TreeSet<>(ThirdPartyMixinTargets.forMixin(mixin).targets().keySet()),
                    mixin + ": spec targets differ from its @Mixin targets");
        }
    }

    @Test
    void endDeterminismMixinsAreTheEndLayoutOnes() {
        Set<String> end = new TreeSet<>();
        for (String mixin : ThirdPartyMixinTargets.guardedMixins()) {
            if (ThirdPartyMixinTargets.isEndDeterminism(mixin)) end.add(mixin.substring(mixin.lastIndexOf('.') + 1));
        }
        assertEquals(new TreeSet<>(List.of(
                "WoverBiomePickerOrderMixin", "WoverBiomePickerSampleMixin", "WoverPossibleBiomesOrderMixin",
                "WoverPossibleBiomesCompatOrderMixin",
                "BetterEndStaticShuffleMixin", "BetterEndWallScatterShuffleMixin", "BetterEndDirPerThreadMixin",
                "BetterEndDirectionsPerThreadMixin", "BetterEndHorizontalPerThreadMixin")), end);
    }

    private static Set<String> annotatedTargets(ClassNode node) {
        Set<String> targets = new TreeSet<>();
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
        if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
        for (AnnotationNode a : annotations) {
            if (!MIXIN_ANNOTATION.equals(a.desc) || a.values == null) continue;
            for (int i = 0; i < a.values.size(); i += 2) {
                String key = (String) a.values.get(i);
                Object value = a.values.get(i + 1); // remap/priority are scalars; only value/targets are lists
                if ("value".equals(key)) {
                    ((List<?>) value).forEach(v -> targets.add(((Type) v).getClassName()));
                } else if ("targets".equals(key)) {
                    ((List<?>) value).forEach(v -> targets.add(((String) v).replace('/', '.')));
                }
            }
        }
        return targets;
    }

    private static ClassNode readClass(String className) throws IOException {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream in = ThirdPartyMixinTargetsTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static JsonObject readConfig(String name) throws IOException {
        Path path = RepoPaths.root().resolve("src/main/resources").resolve(name);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
