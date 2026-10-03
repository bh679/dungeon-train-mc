package games.brennan.dungeontrain.compat.mixinguard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.field;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.invokes;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.method;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.readsStatic;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.readsStaticIn;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.writesStaticIn;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.staticField;

/**
 * What each DT mixin into a third-party library's <b>internals</b> needs to find there. These libraries
 * (WorldWeaver, BCLib, BetterEnd, TerraBlender) are declared with open-ended {@code [floor,)} ranges, so a
 * player can load a newer build that renamed one of those internals. {@code ThirdPartyMixinPlugin} checks
 * every entry here before the mixin applies, and skips a mixin whose targets moved instead of crashing the
 * game. The feature then falls back as {@link Spec#degradesTo} says.
 *
 * <p>{@code ThirdPartyMixinTargetsTest} checks this table against the pinned jars, and checks it lists
 * every mixin in the guarded configs with the same targets as its {@code @Mixin}. After a library bump,
 * a failing test there means a mixin (and this table) needs updating.</p>
 */
public final class ThirdPartyMixinTargets {

    /**
     * The library a checked class belongs to is {@link #libraryOf} of its name, not a property of the
     * mixin: one mixin can hook two libraries (BetterEnd's features and BCLib's {@code StructureErode}).
     *
     * @param targets     the {@code @Mixin} target classes (dotted names) and what each must contain
     * @param alsoUses    non-target classes the mixin body calls into, and what it needs there
     * @param degradesTo  what the player gets while the mixin is skipped
     * @param endDeterminism true if the BetterEnd End only generates the same each boot with this mixin
     */
    public record Spec(Map<String, List<MixinTargetRequirement>> targets,
                       Map<String, List<MixinTargetRequirement>> alsoUses,
                       String degradesTo,
                       boolean endDeterminism) {

        /** {@link #targets} then {@link #alsoUses} — every class the check reads. */
        public Map<String, List<MixinTargetRequirement>> allChecked() {
            Map<String, List<MixinTargetRequirement>> all = new LinkedHashMap<>(targets);
            all.putAll(alsoUses);
            return Map.copyOf(all);
        }
    }

    public static final String MIXIN_PACKAGE = "games.brennan.dungeontrain.mixin.";

    private static final String BE = "org.betterx.betterend.world.";
    private static final String DIRECTIONS = "[Lnet/minecraft/core/Direction;";
    private static final String END_VANILLA =
            "BetterEnd End bands stamp vanilla End (the BetterEnd End would lay out differently each boot)";

    private static final String TENANEA = BE + "features.trees.TenaneaFeature";
    private static final String LUCERNIA = BE + "features.trees.LucerniaFeature";
    private static final String TENANEA_BUSH = BE + "features.bushes.TenaneaBushFeature";
    private static final String BUSH_WITH_OUTER = BE + "features.bushes.BushWithOuterFeature";
    private static final String GEYSER = BE + "features.terrain.GeyserFeature";
    private static final String SULPHURIC_CAVE = BE + "structures.piece.SulphuricCavePiece";
    private static final String WALL_SCATTER = BE + "features.WallScatterFeature";
    private static final String STRUCTURE_ERODE = "org.betterx.bclib.util.StructureErode";
    private static final String BIOME_ISLAND = BE + "features.BiomeIslandFeature";
    private static final String GLOWSHROOM = BE + "features.trees.MossyGlowshroomFeature";
    private static final String ORE_LAYER = BE + "features.terrain.OreLayerFeature";

    private static final String PLACE = "place";
    private static final String PLACE_DESC = "(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z";
    private static final String ISLAND_BLOCK_LAMBDA = "lambda$createSDFIsland$0";
    private static final String ISLAND_NOISE_LAMBDA = "lambda$createSDFIsland$1";
    private static final String BLOCK_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String SDF = "Lorg/betterx/bclib/sdf/SDF;";
    private static final String SDF_PRIMITIVE = "Lorg/betterx/bclib/sdf/primitive/SDFPrimitive;";
    private static final String SDF_OPERATOR = "Lorg/betterx/bclib/sdf/operator/";

    private static final String MHELPER_SHUFFLE_DESC = "([Ljava/lang/Object;Lnet/minecraft/util/RandomSource;)V";
    private static final MixinTargetRequirement CALLS_MHELPER_SHUFFLE =
            invokes(MixinTargetRequirement.Invokes.ANY_METHOD, "org/betterx/bclib/util/MHelper", "shuffle",
                    MHELPER_SHUFFLE_DESC);

    private static final Map<String, Spec> SPECS = Map.ofEntries(
        // ── WorldWeaver ──────────────────────────────────────────────────────────────────────────
        Map.entry(MIXIN_PACKAGE + "wover.WoverBiomePickerOrderMixin", new Spec(
            Map.of("org.betterx.wover.generator.api.biomesource.WoverBiomePicker", List.of(
                invokes("rebuild", "java/util/Set", "forEach", "(Ljava/util/function/Consumer;)V"),
                invokes("consumeSubBiomesForSource", "net/minecraft/core/Registry", "entrySet",
                        "()Ljava/util/Set;"))),
            Map.of(
                "org.betterx.wover.generator.api.biomesource.WoverBiomePicker$PickableBiome", List.of(
                    field("biomeData", "Lorg/betterx/wover/biome/api/data/BiomeData;")),
                "org.betterx.wover.biome.api.data.BiomeData", List.of(
                    field("biomeKey", "Lnet/minecraft/resources/ResourceKey;"))),
            END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "wover.WoverBiomePickerSampleMixin", new Spec(
            Map.of("org.betterx.wover.generator.api.biomesource.WoverBiomePicker", List.of(
                method("getBiomeAt", "(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos;)"
                        + "Lnet/minecraft/core/Holder;"))),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "wover.WoverPossibleBiomesOrderMixin", new Spec(
            Map.of("org.betterx.wover.generator.api.biomesource.WoverBiomeSource", List.of(
                method("collectPossibleBiomes", "()Ljava/util/stream/Stream;"))),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "wover.WoverPossibleBiomesCompatOrderMixin", new Spec(
            Map.of("org.betterx.wover.common.generator.impl.compat.LithostitchedBiomeSourceCompat", List.of(
                invokes("replacePossibleBiomes", "java/util/Set", "copyOf",
                        "(Ljava/util/Collection;)Ljava/util/Set;"))),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "wover.WoverStartupScreensMixin", new Spec(
            Map.of("org.betterx.wover.ui.impl.client.VersionCheckerClient", List.of(
                method("presentUpdateScreen", "(Ljava/util/List;)V"))),
            Map.of(), "WorldWeaver's welcome and update screens show at startup", false)),

        // ── BCLib ────────────────────────────────────────────────────────────────────────────────
        Map.entry(MIXIN_PACKAGE + "bclib.BclibFixPromptMixin", new Spec(
            Map.of("org.betterx.bclib.api.v2.datafixer.DataFixerAPI", List.of(
                method("fixData", "(Ljava/io/File;Ljava/lang/String;ZLjava/util/function/Consumer;"
                        + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;)Z"),
                invokes("fixData", "org/betterx/bclib/api/v2/datafixer/DataFixerAPI", "showBackupWarning",
                        "(Ljava/lang/String;Ljava/util/function/BiConsumer;)V"))),
            Map.of(), "BCLib's world-patch backup prompt shows when opening an older Dungeon Train world", false)),

        // ── BetterEnd ────────────────────────────────────────────────────────────────────────────
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndChorusCosmeticMixin", new Spec(
            Map.of(BE + "generator.GeneratorOptions", List.of(method("changeChorusPlant", "()Z"))),
            Map.of(), "chorus plants use BetterEnd's models on every band", false)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndDebugItemsMixin", new Spec(
            Map.of("org.betterx.betterend.registry.EndItems", List.of(method("ensureStaticallyLoaded", "()V"))),
            Map.of(), "nothing in production (BetterEnd's debug items only load in a dev environment)", false)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndDirPerThreadMixin", new Spec(
            perThreadTargets("DIR", STRUCTURE_ERODE, WALL_SCATTER), Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndDirectionsPerThreadMixin", new Spec(
            perThreadTargets("DIRECTIONS", TENANEA, LUCERNIA, TENANEA_BUSH, BUSH_WITH_OUTER),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndHorizontalPerThreadMixin", new Spec(
            perThreadTargets("HORIZONTAL", GEYSER, SULPHURIC_CAVE), Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndStaticShuffleMixin", new Spec(
            Map.of(TENANEA, List.of(CALLS_MHELPER_SHUFFLE),
                   LUCERNIA, List.of(CALLS_MHELPER_SHUFFLE),
                   TENANEA_BUSH, List.of(CALLS_MHELPER_SHUFFLE),
                   BUSH_WITH_OUTER, List.of(CALLS_MHELPER_SHUFFLE),
                   GEYSER, List.of(CALLS_MHELPER_SHUFFLE),
                   SULPHURIC_CAVE, List.of(CALLS_MHELPER_SHUFFLE),
                   STRUCTURE_ERODE, List.of(CALLS_MHELPER_SHUFFLE)),
            Map.of("org.betterx.bclib.util.MHelper", List.of(method("shuffle", MHELPER_SHUFFLE_DESC))),
            END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndWallScatterShuffleMixin", new Spec(
            Map.of(WALL_SCATTER, List.of(
                staticField("DIR", DIRECTIONS),
                method("shuffle", "(Lnet/minecraft/util/RandomSource;)V"))),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndBiomeIslandPerThreadMixin", new Spec(
            Map.of(BIOME_ISLAND, List.of(
                method(PLACE, PLACE_DESC),
                method("createSDFIsland", "()" + SDF),
                staticField("CENTER", "Lnet/minecraft/core/BlockPos$MutableBlockPos;"),
                staticField("ISLAND", SDF),
                staticField("simplexNoise", "Lorg/betterx/betterend/noise/OpenSimplexNoise;"),
                staticField("topBlock", BLOCK_STATE),
                staticField("underBlock", BLOCK_STATE),
                readsStaticIn(PLACE, "CENTER"),
                readsStaticIn(ISLAND_BLOCK_LAMBDA, "CENTER"),
                readsStaticIn(ISLAND_NOISE_LAMBDA, "CENTER"),
                readsStaticIn(PLACE, "ISLAND"),
                readsStaticIn(ISLAND_NOISE_LAMBDA, "simplexNoise"),
                writesStaticIn(PLACE, "simplexNoise"),
                readsStaticIn(ISLAND_BLOCK_LAMBDA, "topBlock"),
                writesStaticIn(PLACE, "topBlock"),
                readsStaticIn(ISLAND_BLOCK_LAMBDA, "underBlock"),
                writesStaticIn(PLACE, "underBlock"))),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndGlowshroomPerThreadMixin", new Spec(
            Map.of(GLOWSHROOM, List.of(
                staticField("CENTER", "Lorg/joml/Vector3f;"),
                staticField("CONE1", SDF_PRIMITIVE),
                staticField("CONE2", SDF_PRIMITIVE),
                staticField("CONE_GLOW", SDF_PRIMITIVE),
                staticField("ROOTS", SDF_PRIMITIVE),
                staticField("HEAD_POS", SDF_OPERATOR + "SDFTranslate;"),
                staticField("ROOTS_ROT", SDF_OPERATOR + "SDFFlatWave;"),
                staticField("FUNCTION", SDF_OPERATOR + "SDFBinary;"),
                readsStaticIn(PLACE, "CENTER"),
                readsStaticIn("lambda$static$2", "CENTER"),
                readsStaticIn(PLACE, "CONE1"),
                readsStaticIn(PLACE, "CONE2"),
                readsStaticIn(PLACE, "CONE_GLOW"),
                readsStaticIn(PLACE, "ROOTS"),
                readsStaticIn(PLACE, "HEAD_POS"),
                readsStaticIn(PLACE, "ROOTS_ROT"),
                readsStaticIn(PLACE, "FUNCTION"))),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndOreLayerPerThreadMixin", new Spec(
            Map.of(ORE_LAYER, List.of(
                staticField("SPHERE", "Lorg/betterx/bclib/sdf/primitive/SDFSphere;"),
                staticField("NOISE", SDF_OPERATOR + "SDFCoordModify;"),
                staticField("FUNCTION", SDF),
                readsStaticIn(PLACE, "SPHERE"),
                readsStaticIn(PLACE, "NOISE"),
                readsStaticIn(PLACE, "FUNCTION"))),
            Map.of(), END_VANILLA, true)),

        // ── TerraBlender ─────────────────────────────────────────────────────────────────────────
        Map.entry(MIXIN_PACKAGE + "terrablender.RegionsMixin", new Spec(
            Map.of("terrablender.api.Regions", List.of(
                method("register", "(Lnet/minecraft/resources/ResourceLocation;Lterrablender/api/Region;)V"),
                method("register", "(Lnet/minecraft/resources/ResourceLocation;ILterrablender/api/Region;)V"))),
            Map.of(), "TerraBlender regions DT keeps to the vanilla overworld stretches blend into every stretch",
            false)),
        Map.entry(MIXIN_PACKAGE + "terrablender.SurfaceRuleManagerMixin", new Spec(
            Map.of("terrablender.api.SurfaceRuleManager", List.of(
                staticField("surfaceRules", "Ljava/util/Map;"),
                method("getNamespacedRules", "(Lterrablender/api/SurfaceRuleManager$RuleCategory;"
                        + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)"
                        + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;"))),
            Map.of("terrablender.worldgen.surface.NamespacedSurfaceRuleSource", List.of(
                method("<init>", "(Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;Ljava/util/Map;)V"))),
            "vanilla biomes in DT worlds take TerraBlender's default surface rule instead of DT's; the "
                + "bedrock floor gets vanilla's specks up to 4 blocks above it, so chunks generated now show "
                + "a seam at the floor against chunks an existing world generated earlier", false))
    );

    /** Mod id by package prefix, for every class a {@link Spec} checks. */
    private static final Map<String, String> LIBRARY_BY_PACKAGE = Map.of(
            "org.betterx.wover.", "wover",
            "org.betterx.bclib.", "bclib",
            "org.betterx.betterend.", "betterend",
            "terrablender.", "terrablender");

    private ThirdPartyMixinTargets() {}

    /** Spec for {@code mixinClassName} (dotted, fully qualified), or {@code null} if it is not guarded. */
    public static Spec forMixin(String mixinClassName) {
        return SPECS.get(mixinClassName);
    }

    /** Every guarded mixin's fully qualified name. */
    public static java.util.Set<String> guardedMixins() {
        return SPECS.keySet();
    }

    /**
     * The mod id of the library {@code className} (dotted) belongs to, or {@code null} for a class outside
     * the guarded libraries. This is what the WARN blames, so BCLib's classes read as BCLib even when a
     * BetterEnd mixin hooks them.
     */
    public static String libraryOf(String className) {
        return LIBRARY_BY_PACKAGE.entrySet().stream()
                .filter(e -> className.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /** True if skipping {@code mixinClassName} breaks the BetterEnd End's boot-to-boot determinism. */
    public static boolean isEndDeterminism(String mixinClassName) {
        Spec spec = SPECS.get(mixinClassName);
        return spec != null && spec.endDeterminism();
    }

    /** Each target has static field {@code name} and reads it — what a per-thread field redirect needs. */
    private static Map<String, List<MixinTargetRequirement>> perThreadTargets(String name, String... targets) {
        Map<String, List<MixinTargetRequirement>> out = new LinkedHashMap<>();
        for (String target : targets) {
            out.put(target, List.of(staticField(name, DIRECTIONS), readsStatic(name)));
        }
        return Map.copyOf(out);
    }
}
