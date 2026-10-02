package games.brennan.dungeontrain.compat.mixinguard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.field;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.invokes;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.method;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.readsStatic;
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
     * @param modId       the library's mod id (for the version in the WARN)
     * @param targets     the {@code @Mixin} target classes (dotted names) and what each must contain
     * @param alsoUses    non-target classes the mixin body calls into, and what it needs there
     * @param degradesTo  what the player gets while the mixin is skipped
     * @param endDeterminism true if the BetterEnd End only generates the same each boot with this mixin
     */
    public record Spec(String modId,
                       Map<String, List<MixinTargetRequirement>> targets,
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
            "BetterEnd End bands stamp vanilla End instead (the BetterEnd End would lay out differently each boot)";

    private static final String TENANEA = BE + "features.trees.TenaneaFeature";
    private static final String LUCERNIA = BE + "features.trees.LucerniaFeature";
    private static final String TENANEA_BUSH = BE + "features.bushes.TenaneaBushFeature";
    private static final String BUSH_WITH_OUTER = BE + "features.bushes.BushWithOuterFeature";
    private static final String GEYSER = BE + "features.terrain.GeyserFeature";
    private static final String SULPHURIC_CAVE = BE + "structures.piece.SulphuricCavePiece";
    private static final String WALL_SCATTER = BE + "features.WallScatterFeature";
    private static final String STRUCTURE_ERODE = "org.betterx.bclib.util.StructureErode";

    private static final String MHELPER_SHUFFLE_DESC = "([Ljava/lang/Object;Lnet/minecraft/util/RandomSource;)V";
    private static final MixinTargetRequirement CALLS_MHELPER_SHUFFLE =
            invokes(MixinTargetRequirement.Invokes.ANY_METHOD, "org/betterx/bclib/util/MHelper", "shuffle",
                    MHELPER_SHUFFLE_DESC);

    private static final Map<String, Spec> SPECS = Map.ofEntries(
        // ── WorldWeaver ──────────────────────────────────────────────────────────────────────────
        Map.entry(MIXIN_PACKAGE + "wover.WoverBiomePickerOrderMixin", new Spec("wover",
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
        Map.entry(MIXIN_PACKAGE + "wover.WoverBiomePickerSampleMixin", new Spec("wover",
            Map.of("org.betterx.wover.generator.api.biomesource.WoverBiomePicker", List.of(
                method("getBiomeAt", "(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos;)"
                        + "Lnet/minecraft/core/Holder;"))),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "wover.WoverPossibleBiomesOrderMixin", new Spec("wover",
            Map.of("org.betterx.wover.generator.api.biomesource.WoverBiomeSource", List.of(
                method("collectPossibleBiomes", "()Ljava/util/stream/Stream;"))),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "wover.WoverPossibleBiomesCompatOrderMixin", new Spec("wover",
            Map.of("org.betterx.wover.common.generator.impl.compat.LithostitchedBiomeSourceCompat", List.of(
                invokes("replacePossibleBiomes", "java/util/Set", "copyOf",
                        "(Ljava/util/Collection;)Ljava/util/Set;"))),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "wover.WoverStartupScreensMixin", new Spec("wover",
            Map.of("org.betterx.wover.ui.impl.client.VersionCheckerClient", List.of(
                method("presentUpdateScreen", "(Ljava/util/List;)V"))),
            Map.of(), "WorldWeaver's welcome and update screens show at startup", false)),

        // ── BCLib ────────────────────────────────────────────────────────────────────────────────
        Map.entry(MIXIN_PACKAGE + "bclib.BclibFixPromptMixin", new Spec("bclib",
            Map.of("org.betterx.bclib.api.v2.datafixer.DataFixerAPI", List.of(
                method("fixData", "(Ljava/io/File;Ljava/lang/String;ZLjava/util/function/Consumer;"
                        + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;)Z"),
                invokes("fixData", "org/betterx/bclib/api/v2/datafixer/DataFixerAPI", "showBackupWarning",
                        "(Ljava/lang/String;Ljava/util/function/BiConsumer;)V"))),
            Map.of(), "BCLib's world-patch backup prompt shows when opening an older Dungeon Train world", false)),

        // ── BetterEnd ────────────────────────────────────────────────────────────────────────────
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndChorusCosmeticMixin", new Spec("betterend",
            Map.of(BE + "generator.GeneratorOptions", List.of(method("changeChorusPlant", "()Z"))),
            Map.of(), "chorus plants use BetterEnd's models on every band", false)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndDebugItemsMixin", new Spec("betterend",
            Map.of("org.betterx.betterend.registry.EndItems", List.of(method("ensureStaticallyLoaded", "()V"))),
            Map.of(), "nothing in production (BetterEnd's debug items only load in a dev environment)", false)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndDirPerThreadMixin", new Spec("betterend",
            perThreadTargets("DIR", STRUCTURE_ERODE, WALL_SCATTER), Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndDirectionsPerThreadMixin", new Spec("betterend",
            perThreadTargets("DIRECTIONS", TENANEA, LUCERNIA, TENANEA_BUSH, BUSH_WITH_OUTER),
            Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndHorizontalPerThreadMixin", new Spec("betterend",
            perThreadTargets("HORIZONTAL", GEYSER, SULPHURIC_CAVE), Map.of(), END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndStaticShuffleMixin", new Spec("betterend",
            Map.of(TENANEA, List.of(CALLS_MHELPER_SHUFFLE),
                   LUCERNIA, List.of(CALLS_MHELPER_SHUFFLE),
                   TENANEA_BUSH, List.of(CALLS_MHELPER_SHUFFLE),
                   BUSH_WITH_OUTER, List.of(CALLS_MHELPER_SHUFFLE),
                   GEYSER, List.of(CALLS_MHELPER_SHUFFLE),
                   SULPHURIC_CAVE, List.of(CALLS_MHELPER_SHUFFLE),
                   STRUCTURE_ERODE, List.of(CALLS_MHELPER_SHUFFLE)),
            Map.of("org.betterx.bclib.util.MHelper", List.of(method("shuffle", MHELPER_SHUFFLE_DESC))),
            END_VANILLA, true)),
        Map.entry(MIXIN_PACKAGE + "betterend.BetterEndWallScatterShuffleMixin", new Spec("betterend",
            Map.of(WALL_SCATTER, List.of(
                staticField("DIR", DIRECTIONS),
                method("shuffle", "(Lnet/minecraft/util/RandomSource;)V"))),
            Map.of(), END_VANILLA, true)),

        // ── TerraBlender ─────────────────────────────────────────────────────────────────────────
        Map.entry(MIXIN_PACKAGE + "terrablender.RegionsMixin", new Spec("terrablender",
            Map.of("terrablender.api.Regions", List.of(
                method("register", "(Lnet/minecraft/resources/ResourceLocation;Lterrablender/api/Region;)V"),
                method("register", "(Lnet/minecraft/resources/ResourceLocation;ILterrablender/api/Region;)V"))),
            Map.of(), "TerraBlender regions DT keeps to the vanilla overworld stretches blend into every stretch",
            false)),
        Map.entry(MIXIN_PACKAGE + "terrablender.SurfaceRuleManagerMixin", new Spec("terrablender",
            Map.of("terrablender.api.SurfaceRuleManager", List.of(
                staticField("surfaceRules", "Ljava/util/Map;"),
                method("getNamespacedRules", "(Lterrablender/api/SurfaceRuleManager$RuleCategory;"
                        + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)"
                        + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;"))),
            Map.of("terrablender.worldgen.surface.NamespacedSurfaceRuleSource", List.of(
                method("<init>", "(Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;Ljava/util/Map;)V"))),
            "vanilla biomes in DT worlds take TerraBlender's default surface rule instead of DT's", false))
    );

    private ThirdPartyMixinTargets() {}

    /** Spec for {@code mixinClassName} (dotted, fully qualified), or {@code null} if it is not guarded. */
    public static Spec forMixin(String mixinClassName) {
        return SPECS.get(mixinClassName);
    }

    /** Every guarded mixin's fully qualified name. */
    public static java.util.Set<String> guardedMixins() {
        return SPECS.keySet();
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
