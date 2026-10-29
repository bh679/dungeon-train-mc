package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Sidecar JSON ({@code <id>.variants.json}) that pairs with each carriage
 * variant's {@code .nbt} template — the <b>codec</b> every kind's sidecar shares
 * ({@link #parseCellValue} / {@link #appendCellJson} per cell, {@link #parseVariantElement} /
 * {@link #appendVariantJson} per candidate, the deterministic {@code pickIndex*} mixers and the
 * empty-placeholder sentinel), plus the carriage kind's entry points onto {@link VariantSidecarStore}.
 * The document itself is a {@link VariantDoc}. Each entry maps a carriage-local
 * {@link BlockPos} to a list of candidate {@link BlockState}s — one of which
 * is picked deterministically at spawn time based on the world seed and the
 * carriage index.
 *
 * <p>The NBT template stays the source of truth for the carriage's "default"
 * block at every position; this sidecar only describes positions the author
 * wants to randomise. Missing sidecar = no variants, full backward compat
 * with every existing template on disk or in the mod jar.</p>
 *
 * <p>Follows the three-tier lookup used by {@link CarriageTemplateStore}:
 * per-install config dir ({@link #configPathFor}) overrides bundled resource
 * ({@link #bundledResourceFor}). Unlike the NBT store, there is no hardcoded
 * fallback — a variant with no sidecar simply has no random positions.</p>
 *
 * <p>v4 schema (current):
 * <pre>
 * {
 *   "schemaVersion": 4,
 *   "variants": {
 *     "3,1,2": [
 *       "minecraft:stone",
 *       { "state": "minecraft:bookshelf" },
 *       { "state": "minecraft:chest[facing=west]", "nbt": "{Items:[...]}" },
 *       { "state": "minecraft:cobblestone", "weight": 3 }
 *     ],
 *     "4,1,2": {
 *       "lockId": 1,
 *       "states": [ "minecraft:stone", "minecraft:cobblestone" ]
 *     }
 *   }
 * }
 * </pre>
 * Keys use local coords (same basis as {@code StructureTemplate} block infos).
 * Each cell value is either a bare states array (lockId=0; v3-shape) or an
 * object {@code {lockId, states:[...]}} when {@code lockId>0}. Each candidate
 * inside the states array is either a bare BlockState string or a
 * {@code {state, nbt?, weight?}} object. v1 / v2 / v3 files load
 * unchanged; the v4 writer emits the cell-object form only when {@code
 * lockId>0}, so older files round-trip diff-clean.</p>
 */
public final class CarriageVariantBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Current schema version written by {@link #save}.
     * <ul>
     *   <li>v1 — bare BlockState strings.</li>
     *   <li>v2 — adds optional per-entry BlockEntity NBT.</li>
     *   <li>v3 — adds optional per-entry {@code weight} (≥1, default 1)
     *       and a per-entry {@code locked} flag (since dropped). Older
     *       files load with the defaults and round-trip diff-clean since
     *       the writer only emits object form when one of {NBT,
     *       non-default weight} is set.</li>
     *   <li>v4 — adds optional per-cell {@code lockId} (≥0, default 0).
     *       Cells with the same non-zero {@code lockId} share a runtime
     *       random pick at spawn (called once per group, all cells in
     *       the group render the same index). The cell's value is now
     *       either a bare states array (v3 form, lockId=0) or an object
     *       {@code {"states":[...], "lockId":N}} (lockId&gt;0). v3
     *       per-entry {@code locked} fields are silently ignored on
     *       read.</li>
     *   <li>v5 — adds optional per-entry {@code rotation} object
     *       {@code {"mode": "lock|random|options", "dirs": ["east", ...]}}.
     *       Default rotation (random with empty mask) is omitted from
     *       JSON, so v3/v4 entries round-trip diff-clean.</li>
     *   <li>v6 — adds optional per-entry {@code lootPrefab} string that
     *       links the variant to a saved entry in {@code LootPrefabStore}.
     *       The editor renders the prefab id as the row label and the
     *       spawn pipeline rolls contents from the linked pool. The field
     *       is omitted when null, so v5 entries round-trip diff-clean.</li>
     *   <li>v7 — adds optional per-entry {@code entity} string (entity-type
     *       registry id) and optional {@code nbt} entity NBT for mob
     *       variants. When present, the cell rolls AIR and a deferred
     *       entity pass spawns the mob (subject to the existing 48-block
     *       distance gate). The {@code state} field is omitted from JSON
     *       for mob entries (the schema infers the AIR sentinel). v6
     *       entries load with {@code entityId == null} and re-save
     *       diff-clean.
     *       <p>v7 also gained an additive (no schema bump) optional
     *       {@code "half"} field: {@code "top" | "random" | "bottom"} for
     *       blocks with {@code SLAB_TYPE} or {@code HALF} (slabs, stairs,
     *       trapdoors). When absent, the value is derived from the captured
     *       state + rotation mode: LOCK preserves captured half (TOP or
     *       BOTTOM), RANDOM rolls TOP/BOTTOM (matching the historical
     *       {@code randomizeFlip} call). Unknown values silently coerce to
     *       {@code random}. The {@code "double"} half — used by full-height
     *       slabs — is not representable; capture a DOUBLE slab as a
     *       separate variant with the field omitted.</p></li>
     *   <li>v8 — adds an optional per-entry {@code difficulty} object
     *       ({@code {"min": N, "max": N|"all"}}) on mob entries. At spawn the
     *       cell drops eggs whose band excludes the carriage's difficulty tier
     *       before the weighted pick. Omitted when the band is the default
     *       ({@code min 0 / max all}), so v7 entries round-trip diff-clean.</li>
     *   <li>v9 — adds an optional per-entry {@code groupRef} (≥ 0, default 0):
     *       a lock-group reference. When the cell's roll lands on such an
     *       entry, the block placed is whatever the lock group with that id
     *       resolved to, so one pool can defer to another instead of
     *       duplicating it. Dead references (missing group, self, cycle) are
     *       dropped from the pool before the roll. Omitted when 0, so v8
     *       entries round-trip diff-clean. See {@link VariantGroupRefs}.</li>
     *   <li>v10 — adds two optional per-<b>cell</b> fields inside the v4 cell
     *       object, both about a room that repeats and both meaningless to
     *       every other sidecar. {@code "roll"} ({@link VariantCopyRoll}) says
     *       how the cell rolls across the room's copies: {@code default} (the
     *       default, omitted — whatever the room's own Copies setting says),
     *       {@code exact} or {@code vary}. {@code "scope"}
     *       ({@link VariantCopyScope}) says which tiles the cell applies in at
     *       all: {@code both} (the default, omitted), {@code copies} or
     *       {@code not_copies}. Both are absent-is-default, so v9 files
     *       round-trip diff-clean. A short-lived boolean {@code "reroll": true}
     *       predates {@code "roll"} on the same branch and still reads, as
     *       {@code vary}. See {@code TrackVariantBlocks#copyRollAt} /
     *       {@code TrackVariantBlocks#copyScopeAt}.</li>
     * </ul>
     */
    public static final int CURRENT_SCHEMA_VERSION = 10;

    /** The v10 per-cell {@link VariantCopyRoll} field's key. Absent means {@code default}. */
    static final String ROLL_KEY = "roll";

    /**
     * The boolean this setting first shipped as on the branch, before it grew a third state.
     * Read as {@link VariantCopyRoll#VARY}, never written. Kept because rooms authored between the
     * two carry it, and re-reading one as "follows the room" would silently undo the author's
     * choice.
     */
    static final String LEGACY_REROLL_KEY = "reroll";

    /** The v10 per-cell {@link VariantCopyScope} field's key. Absent means {@code both}. */
    static final String SCOPE_KEY = "scope";

    /**
     * Per-cell {@link VariantSpan} field's key (additive, no schema bump): how a single block
     * fills the two spaces of a cell that also holds a door / bed / tall plant. Absent means
     * {@code AUTO}; tokens are {@link VariantSpan#toToken}.
     */
    static final String SPAN_KEY = "span";

    static final String SUBDIR = "templates";
    static final String EXT = ".variants.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    /** Minimum candidates per entry — a single-state "variant" is just a fixed block. */
    public static final int MIN_STATES_PER_ENTRY = 2;

    /**
     * The empty-placeholder sentinel — the state a variant entry carries to mean "leave this
     * position empty / air at spawn time", and the block the editor stands in such a cell:
     * {@link games.brennan.dungeontrain.block.VariantPlaceholderBlock}. Every site that writes
     * a sentinel goes through here so the canonical block lives in one place.
     *
     * <p>Falls back to a vanilla command block — the sentinel's previous incarnation — when the
     * mod block is not registered, which is only the case under unit tests that bootstrap vanilla
     * alone. {@link #isEmptyPlaceholder} accepts both, so the fallback is a sentinel too.</p>
     */
    public static BlockState emptyPlaceholder() {
        if (ModBlocks.VARIANT_PLACEHOLDER.isBound()) {
            return ModBlocks.VARIANT_PLACEHOLDER.get().defaultBlockState();
        }
        return Blocks.COMMAND_BLOCK.defaultBlockState();
    }

    /**
     * Returns true if {@code state} is the empty-placeholder sentinel used in variant lists to
     * mean "leave this position empty / air at spawn time." True for
     * {@link #emptyPlaceholder()} and for the legacy form, any of the three vanilla
     * command-block kinds ({@link #isLegacyEmptyPlaceholder}) — bundled sidecars and template
     * NBTs written before the mod block existed still carry those. The translation to
     * {@code Blocks.AIR} happens in the apply path.
     */
    public static boolean isEmptyPlaceholder(BlockState state) {
        if (state == null) return false;
        return (ModBlocks.VARIANT_PLACEHOLDER.isBound() && state.is(ModBlocks.VARIANT_PLACEHOLDER.get()))
            || isLegacyEmptyPlaceholder(state);
    }

    /**
     * The sentinel as it was before {@code dungeontrain:variant_placeholder}: any vanilla command
     * block (impulse, chain, repeating). {@link VariantState}'s canonical constructor rewrites
     * these to {@link #emptyPlaceholder()} on load, so a sidecar migrates the first time it is
     * re-saved; the editor preview ticker does the same for the world block of a legacy cell.
     */
    public static boolean isLegacyEmptyPlaceholder(BlockState state) {
        if (state == null) return false;
        return state.is(Blocks.COMMAND_BLOCK)
            || state.is(Blocks.CHAIN_COMMAND_BLOCK)
            || state.is(Blocks.REPEATING_COMMAND_BLOCK);
    }


    /** Local position + candidate variants. Ordering of {@code states} is preserved as written. */
    public record Entry(BlockPos localPos, List<VariantState> states) {
        public Entry {
            if (localPos == null) throw new IllegalArgumentException("localPos");
            if (states == null || states.size() < MIN_STATES_PER_ENTRY) {
                throw new IllegalArgumentException(
                    "entry at " + localPos + " must list at least " + MIN_STATES_PER_ENTRY + " states");
            }
            for (VariantState s : states) {
                if (s == null) throw new IllegalArgumentException("null state in entry " + localPos);
            }
            states = List.copyOf(states);
        }
    }

    // ---------- Store facade ----------
    //
    // The document itself is a VariantDoc, persisted by VariantSidecarStore under a
    // CarriageAddress; the statics below keep this class's entry points for the carriage kind.

    /** Where carriage {@code id}'s sidecar lives: {@code templates/<pool path>.variants.json}. */
    public record CarriageAddress(String id) implements VariantAddress {
        @Override public String cacheKey() { return "carriage:" + id; }
        @Override public String contextId() { return id; }
        @Override public String userSubdir() { return SUBDIR; }
        @Override public String fileName() { return games.brennan.dungeontrain.train.ShellPool.path(id) + EXT; }
        @Override public Path userPath() { return UserContentPaths.dir(SUBDIR).resolve(fileName()); }
        @Override public String bundledResource() { return RESOURCE_PREFIX + fileName(); }
        @Override public Path sourcePath() {
            Path projectRoot = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().getParent();
            if (projectRoot == null) return null;
            return projectRoot.resolve(SOURCE_REL_PATH).resolve(fileName());
        }
    }

    public static VariantAddress address(CarriageVariant variant) {
        return new CarriageAddress(variant.id());
    }

    public static VariantAddress address(String id) {
        return new CarriageAddress(id);
    }

    /** Detached empty document — tests and paths with no variant behind them. */
    public static VariantDoc empty() {
        return VariantDoc.empty();
    }

    /** On-disk path for the config-dir sidecar matching {@code variant}. */
    public static Path configPathFor(CarriageVariant variant) {
        return address(variant).userPath();
    }

    /** Classpath resource for the bundled sidecar matching {@code variant} (only exists for shipped variants). */
    public static String bundledResourceFor(CarriageVariant variant) {
        return address(variant).bundledResource();
    }

    /** Source-tree path for the bundled sidecar — only writable in a {@code ./gradlew runClient} dev checkout. */
    public static Path sourcePathFor(CarriageType type) {
        return sourcePathForVariant(CarriageVariant.of(type));
    }

    /** Variant-keyed source-tree path — works for both built-ins and customs. */
    public static Path sourcePathForVariant(CarriageVariant variant) {
        Path p = address(variant).sourcePath();
        if (p == null) {
            throw new IllegalStateException("Cannot resolve source directory — FMLPaths.GAMEDIR has no parent.");
        }
        return p;
    }

    /**
     * Load the sidecar for {@code variant} — per-install config dir first, then bundled resource —
     * bounded to {@code dims}. Cached by variant id until a save or {@link #clearCache}.
     */
    public static VariantDoc loadFor(CarriageVariant variant, CarriageDims dims) {
        net.minecraft.core.Vec3i footprint = dims == null ? null
            : new net.minecraft.core.Vec3i(dims.length(), dims.height(), dims.width());
        return VariantSidecarStore.loadFor(address(variant), footprint);
    }

    /** Delete the config-dir sidecar and invalidate the cache. Used by the editor reset path. */
    public static boolean delete(CarriageVariant variant) throws IOException {
        return VariantSidecarStore.delete(address(variant));
    }

    /** Move-or-carry the sidecar from {@code sourceId} to {@code targetId} — {@link VariantSidecarStore#rename}. */
    public static boolean rename(String sourceId, String targetId) throws IOException {
        return VariantSidecarStore.rename(address(sourceId), address(targetId));
    }

    /** Invalidate the per-variant cache entry so the next {@link #loadFor} re-reads disk. */
    public static void invalidate(String variantId) {
        VariantSidecarStore.invalidate(address(variantId));
    }

    /** Drop all cached sidecars — wired to {@code ServerStoppedEvent} by the template store. */
    public static void clearCache() {
        VariantSidecarStore.clearCache();
    }

    /** {@link VariantDoc#parsePos}. */
    public static BlockPos parsePos(String key) {
        return VariantDoc.parsePos(key);
    }

    /** {@link VariantDoc#formatPos}. */
    static String formatPos(BlockPos p) {
        return VariantDoc.formatPos(p);
    }

    /**
     * Parse one element of a per-cell candidate array — either a bare string
     * (v1, BlockState only) or an object {@code {state, nbt?, weight?, locked?}}
     * (v2 / v3). Returns {@code null} on parse failure (caller logs).
     */
    public static VariantState parseVariantElement(JsonElement el,
                                            HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks,
                                            String contextId, BlockPos contextPos) {
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            return parseStateString(el.getAsString(), blocks, contextId, contextPos);
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            // v7 mob entry — has "entity" instead of "state". The picker treats
            // the cell as AIR (the canonical VariantState constructor stamps
            // the empty-placeholder sentinel) and a deferred entity pass spawns
            // the mob.
            if (obj.has("entity") && obj.get("entity").isJsonPrimitive()
                && obj.get("entity").getAsJsonPrimitive().isString()) {
                String raw = obj.get("entity").getAsString().trim();
                net.minecraft.resources.ResourceLocation eid =
                    net.minecraft.resources.ResourceLocation.tryParse(raw);
                if (eid == null) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: bad entity id '{}', skipping.",
                        contextId, contextPos, raw);
                    return null;
                }
                CompoundTag mobNbt = null;
                if (obj.has("nbt") && obj.get("nbt").isJsonPrimitive()
                    && obj.get("nbt").getAsJsonPrimitive().isString()) {
                    try {
                        mobNbt = TagParser.parseTag(obj.get("nbt").getAsString());
                    } catch (Exception e) {
                        LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: could not parse mob 'nbt' SNBT ({}), dropping NBT.",
                            contextId, contextPos, e.getMessage());
                    }
                }
                int mobWeight = 1;
                if (obj.has("weight") && obj.get("weight").isJsonPrimitive()
                    && obj.get("weight").getAsJsonPrimitive().isNumber()) {
                    int rawW = obj.get("weight").getAsInt();
                    mobWeight = rawW < 1 ? 1 : rawW;
                }
                VariantRotation mobRot = parseRotation(obj.get("rotation"), false, contextId, contextPos);
                // Mob entries never have SLAB_TYPE / HALF (the state is the
                // empty-placeholder sentinel), but parse the field for round-trip
                // fidelity in case future schema lets mobs carry it.
                VariantHalf mobHalf = parseHalf(obj.get("half"), contextId, contextPos);
                if (mobHalf == null) mobHalf = VariantHalf.NONE;
                VariantDifficulty mobDiff = parseDifficulty(obj.get("difficulty"), contextId, contextPos);
                // Optional equipment-loadout link for entity entries (armor
                // stands point at a CATEGORY_ARMOR_STAND loot prefab). Parsed
                // the same way as the block branch below so it round-trips.
                String mobLootPrefab = null;
                if (obj.has("lootPrefab") && obj.get("lootPrefab").isJsonPrimitive()
                    && obj.get("lootPrefab").getAsJsonPrimitive().isString()) {
                    String rawPrefab = obj.get("lootPrefab").getAsString().trim();
                    if (!rawPrefab.isEmpty()) mobLootPrefab = rawPrefab;
                }
                VariantState mob = VariantState.ofMob(eid, mobNbt, mobWeight, mobRot);
                return mob.withHalf(mobHalf).withDifficulty(mobDiff).withLinkedLootPrefabId(mobLootPrefab)
                    .withCondition(parseCondition(obj.get("if"), contextId, contextPos));
            }
            if (!obj.has("state") || !obj.get("state").isJsonPrimitive()) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: object entry missing 'state' field, skipping.",
                    contextId, contextPos);
                return null;
            }
            VariantState base = parseStateString(obj.get("state").getAsString(), blocks, contextId, contextPos);
            if (base == null) return null;
            CompoundTag nbt = null;
            if (obj.has("nbt") && obj.get("nbt").isJsonPrimitive()
                && obj.get("nbt").getAsJsonPrimitive().isString()) {
                String snbt = obj.get("nbt").getAsString();
                try {
                    nbt = TagParser.parseTag(snbt);
                } catch (Exception e) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: could not parse 'nbt' SNBT ({}), dropping NBT.",
                        contextId, contextPos, e.getMessage());
                }
            }
            int weight = 1;
            if (obj.has("weight") && obj.get("weight").isJsonPrimitive()
                && obj.get("weight").getAsJsonPrimitive().isNumber()) {
                int raw = obj.get("weight").getAsInt();
                weight = raw < 1 ? 1 : raw;
            }
            VariantRotation rotation = parseRotation(obj.get("rotation"),
                RotationApplier.isCompass(base.state()), contextId, contextPos);
            String lootPrefab = null;
            if (obj.has("lootPrefab") && obj.get("lootPrefab").isJsonPrimitive()
                && obj.get("lootPrefab").getAsJsonPrimitive().isString()) {
                String raw = obj.get("lootPrefab").getAsString().trim();
                if (!raw.isEmpty()) lootPrefab = raw;
            }
            // v7 additive field — null when the entry predates "half" or the
            // field is malformed. migrateHalfFromRotation derives the value
            // from the captured state + rotation mode so historical prefabs
            // spawn identically (LOCK preserves captured half, RANDOM keeps
            // rolling).
            VariantHalf parsedHalf = parseHalf(obj.get("half"), contextId, contextPos);
            VariantHalf half = parsedHalf != null
                ? parsedHalf
                : migrateHalfFromRotation(base.state(), rotation);
            // v9 lock-group reference. The entry keeps its own state as the
            // editor placeholder; the ref is what the picker follows.
            int groupRef = 0;
            if (obj.has("groupRef") && obj.get("groupRef").isJsonPrimitive()
                && obj.get("groupRef").getAsJsonPrimitive().isNumber()) {
                int rawRef = obj.get("groupRef").getAsInt();
                groupRef = rawRef < 0 ? 0 : rawRef;
            }
            // Additive redstone-toggle flag. Absent → derive from the captured
            // state so a hand-authored open=true / lit=true entry keeps
            // spawning active instead of being forced off by the default.
            VariantActive parsedActive = parseActive(obj.get("active"), contextId, contextPos);
            VariantActive active = parsedActive != null
                ? parsedActive
                : migrateActiveFromState(base.state());
            // v3 entries had a per-entry "locked" field; v4 moved locking
            // to the cell level. Old "locked" values are silently dropped
            // on read — the file rewrites cleanly without it.
            // Additive fence / wall / pane connect mode. Absent → Default
            // (place the captured arms), so older files spawn unchanged.
            VariantConnect.Mode connect = parseConnect(obj.get("connect"), contextId, contextPos);
            // Additive column growth. Absent → off (a single block), so older
            // files spawn unchanged.
            VariantGrowth growth = parseGrowth(obj.get("growth"), contextId, contextPos);
            // Additive painting size / picture. Absent → off (the captured
            // block, cell by cell), so older files spawn unchanged.
            VariantPainting painting = parsePainting(obj.get("painting"), contextId, contextPos);
            // Additive: the size of the painting this cell was captured from, cleared at every spawn.
            JsonElement hungEl = obj.get("hung");
            HungPainting hung = hungEl != null && hungEl.isJsonPrimitive()
                ? HungPainting.parse(hungEl.getAsString()) : HungPainting.NONE;
            // Additive placement condition. Absent → always eligible, so older files spawn unchanged.
            VariantCondition condition = parseCondition(obj.get("if"), contextId, contextPos);
            return new VariantState(base.state(), nbt, weight, rotation, lootPrefab, null, half,
                VariantDifficulty.NONE, groupRef, active, connect, growth, painting, hung,
                groupRef > 0 ? parseGroupStates(obj.get("groupStates"), blocks, contextId, contextPos) : List.of(),
                condition);
        }
        LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unrecognized entry {}, skipping.",
            contextId, contextPos, el);
        return null;
    }

    /**
     * A reference row's copy of its group's options ({@code "groupStates"}); empty when absent or
     * malformed. Unparseable options are skipped one by one, like the cell's own list.
     */
    private static List<VariantState> parseGroupStates(@javax.annotation.Nullable JsonElement el,
                                                       HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks,
                                                       String contextId, BlockPos contextPos) {
        if (el == null || !el.isJsonArray()) return List.of();
        List<VariantState> out = new ArrayList<>();
        for (JsonElement option : el.getAsJsonArray()) {
            VariantState parsed = parseVariantElement(option, blocks, contextId, contextPos);
            if (parsed != null) out.add(parsed);
        }
        return out;
    }

    private static VariantState parseStateString(String raw,
                                                 HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks,
                                                 String contextId, BlockPos contextPos) {
        try {
            BlockStateParser.BlockResult parsed = BlockStateParser.parseForBlock(blocks, raw, false);
            // A bare string can still carry open=true / lit=true from a hand
            // edit or an older capture of a world block — keep it active.
            return VariantState.of(parsed.blockState())
                .withActive(migrateActiveFromState(parsed.blockState()));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: could not parse '{}' ({}), skipping.",
                contextId, contextPos, raw, e.getMessage());
            return null;
        }
    }


    /**
     * Uniform-distribution index picker (legacy / test surface). Kept for
     * the existing schema-version unit tests that operate on raw {@code size}
     * without constructing {@link VariantState} instances. New callers
     * should use {@link #pickIndexWeighted}.
     *
     * <p>Mirrors {@code CarriagePlacer.seededPick}'s golden-ratio mix,
     * with an additional {@code 0xBF58476D1CE4E5B9L} multiplier folding the
     * block position so two adjacent positions at the same carriage index
     * aren't correlated.</p>
     */
    public static int pickIndex(BlockPos localPos, long worldSeed, int carriageIndex, int size) {
        if (size <= 0) throw new IllegalArgumentException("size must be > 0");
        return seededRandom(localPos, worldSeed, carriageIndex).nextInt(size);
    }

    /**
     * Weighted picker over a list of {@link VariantState}. Materialises
     * weights to an {@code int[]} and delegates to
     * {@link #pickIndexFromWeights} so the picker core stays unit-testable
     * without a Forge/MC bootstrap.
     *
     * <p>Caller responsibility: the {@code states} list must be non-empty
     * and contain no {@link VariantState#locked} entries (otherwise the
     * caller should short-circuit; weights on locked entries don't matter).</p>
     */
    public static int pickIndexWeighted(BlockPos localPos, long worldSeed, int carriageIndex, List<VariantState> states) {
        if (states.isEmpty()) throw new IllegalArgumentException("states must be non-empty");
        int[] weights = new int[states.size()];
        for (int i = 0; i < states.size(); i++) weights[i] = states.get(i).weight();
        return pickIndexFromWeights(localPos, worldSeed, carriageIndex, weights);
    }

    /**
     * Pure-data weighted picker — testable without constructing real
     * {@link VariantState} (and therefore real {@link net.minecraft.world.level.block.state.BlockState})
     * instances. Sums the weights, draws a uniform {@code [0, total)} from
     * the deterministic seed mix, walks the array to find the bucket.
     *
     * <p>List order is stable (insertion order from the LinkedHashMap-backed
     * sidecar), so a given seed always selects the same bucket. Determinism
     * contract: same {@code (worldSeed, carriageIndex, localPos, weights)}
     * → same result across reloads.</p>
     */
    public static int pickIndexFromWeights(BlockPos localPos, long worldSeed, int carriageIndex, int[] weights) {
        if (weights.length == 0) throw new IllegalArgumentException("weights must be non-empty");
        int total = 0;
        for (int w : weights) {
            // Defensive clamp — VariantState's canonical constructor already
            // enforces ≥ 1, but external callers might pass raw values.
            if (w < 1) w = 1;
            total += w;
        }
        // Weights are clamped to [1, ~99] and entry counts are ≤ 32, so
        // total fits comfortably in int.
        int draw = seededRandom(localPos, worldSeed, carriageIndex).nextInt(total);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += Math.max(1, weights[i]);
            if (draw < acc) return i;
        }
        // Fallback for any rounding edge case — should never trigger because acc reaches total.
        return weights.length - 1;
    }

    /**
     * Same seed mix used by both pick paths so the uniform / weighted call
     * sites land on the same draw for backward-compat behaviour
     * verification: a v2 sidecar (no weights) re-loaded as v3 with all
     * weights=1 picks identically to the v2 path.
     */
    private static Random seededRandom(BlockPos localPos, long worldSeed, int carriageIndex) {
        long posHash = (((long) localPos.getX() * 31L + localPos.getY()) * 31L + localPos.getZ());
        long seed = worldSeed
            ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L)
            ^ (posHash * 0xBF58476D1CE4E5B9L);
        return new Random(seed);
    }

    /**
     * Lock-group variant of {@link #pickIndexFromWeights}: hashes the
     * lock-id where the unlocked path hashes the local position. Every
     * cell that shares {@code lockId} therefore draws the same bucket,
     * which is exactly the "all locked cells render the same block at
     * spawn" contract.
     */
    public static int pickIndexFromLockGroup(int lockId, long worldSeed, int carriageIndex, int[] weights) {
        if (weights.length == 0) throw new IllegalArgumentException("weights must be non-empty");
        if (lockId <= 0) throw new IllegalArgumentException("lockId must be > 0");
        int total = 0;
        for (int w : weights) {
            if (w < 1) w = 1;
            total += w;
        }
        long seed = worldSeed
            ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L)
            ^ ((long) lockId * 0xBF58476D1CE4E5B9L);
        int draw = new Random(seed).nextInt(total);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += Math.max(1, weights[i]);
            if (draw < acc) return i;
        }
        return weights.length - 1;
    }

    /**
     * {@link #pickIndexFromWeights} where a weight of 0 means "never" rather than being clamped to 1 —
     * for weights adjusted at stamp time (see {@code LiveTvWeighting}). Same seed mix, so a column with
     * no zero in it picks exactly as {@link #pickIndexFromWeights} does. Returns {@code -1} when every
     * weight is 0; the caller then rolls the authored weights instead.
     */
    public static int pickIndexFromWeightsAllowZero(BlockPos localPos, long worldSeed, int carriageIndex, int[] weights) {
        return drawAllowZero(seededRandom(localPos, worldSeed, carriageIndex), weights);
    }

    /** {@link #pickIndexFromLockGroup} with the zero rule of {@link #pickIndexFromWeightsAllowZero}. */
    public static int pickIndexFromLockGroupAllowZero(int lockId, long worldSeed, int carriageIndex, int[] weights) {
        if (lockId <= 0) throw new IllegalArgumentException("lockId must be > 0");
        long seed = worldSeed
            ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L)
            ^ ((long) lockId * 0xBF58476D1CE4E5B9L);
        return drawAllowZero(new Random(seed), weights);
    }

    private static int drawAllowZero(Random random, int[] weights) {
        if (weights.length == 0) throw new IllegalArgumentException("weights must be non-empty");
        int total = 0;
        for (int w : weights) total += Math.max(0, w);
        if (total <= 0) return -1;
        int draw = random.nextInt(total);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += Math.max(0, weights[i]);
            if (draw < acc) return i;
        }
        return weights.length - 1;
    }

    /**
     * Parsed cell — what {@link #parseCellValue} returns. {@code states}
     * is the candidate list, {@code lockId} is the v4 cell-level lock-id
     * (0 when unlocked or for v1/v2/v3 array-form cells), and
     * {@code roll} / {@code scope} are the two v10 repeating-room fields, at
     * their defaults for every cell that does not carry them — which is every
     * cell authored before v10.
     */
    public record ParsedCell(List<VariantState> states, int lockId, VariantCopyRoll roll,
                             VariantCopyScope scope, VariantSpan span) {

        public ParsedCell {
            if (roll == null) roll = VariantCopyRoll.DEFAULT;
            if (scope == null) scope = VariantCopyScope.BOTH;
            if (span == null) span = VariantSpan.NONE;
        }

        /** Four-arg form for callers that predate the per-cell {@link VariantSpan}. */
        public ParsedCell(List<VariantState> states, int lockId, VariantCopyRoll roll, VariantCopyScope scope) {
            this(states, lockId, roll, scope, VariantSpan.NONE);
        }

        /** Two-arg form for the cells that cannot repeat — every sidecar but a portal room's. */
        public ParsedCell(List<VariantState> states, int lockId) {
            this(states, lockId, VariantCopyRoll.DEFAULT, VariantCopyScope.BOTH);
        }
    }

    /**
     * Parse a {@code "x,y,z" → value} JSON entry into a {@link ParsedCell}.
     * Accepts both forms:
     * <ul>
     *   <li>v3 / pre-v4 — bare array of state elements; {@code lockId = 0}.</li>
     *   <li>v4 — object {@code {"lockId":N, "states":[...]}}; lockId is read
     *       from the object (≥0; negatives clamped to 0).</li>
     *   <li>v10 — the same object may carry {@code "roll"} (how the cell rolls
     *       across a repeating room's copies) and {@code "scope"} (which tiles
     *       of that room it applies in). Both absent read as their defaults, so
     *       every cell written before v10 keeps following its room, in every
     *       tile. The superseded {@code "reroll": true} reads as
     *       {@code roll: vary}.</li>
     * </ul>
     * Returns {@code null} when the value is malformed (caller logs).
     * Used by all four block-variant sidecars
     * ({@link CarriageVariantBlocks}, {@link CarriagePartVariantBlocks},
     * {@link CarriageContentsVariantBlocks},
     * {@code TrackVariantBlocks}).
     */
    public static ParsedCell parseCellValue(JsonElement value,
                                            HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks,
                                            String contextId, BlockPos contextPos) {
        JsonArray arr;
        int lockId = 0;
        VariantCopyRoll roll = VariantCopyRoll.DEFAULT;
        VariantCopyScope scope = VariantCopyScope.BOTH;
        VariantSpan span = VariantSpan.NONE;
        if (value.isJsonArray()) {
            arr = value.getAsJsonArray();
        } else if (value.isJsonObject()) {
            JsonObject cellObj = value.getAsJsonObject();
            if (!cellObj.has("states") || !cellObj.get("states").isJsonArray()) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {}: cell at {} missing 'states' array, skipping.",
                    contextId, contextPos);
                return null;
            }
            arr = cellObj.getAsJsonArray("states");
            if (cellObj.has("lockId") && cellObj.get("lockId").isJsonPrimitive()
                && cellObj.get("lockId").getAsJsonPrimitive().isNumber()) {
                int raw = cellObj.get("lockId").getAsInt();
                lockId = raw < 0 ? 0 : raw;
            }
            if (cellObj.has(LEGACY_REROLL_KEY) && cellObj.get(LEGACY_REROLL_KEY).isJsonPrimitive()
                && cellObj.get(LEGACY_REROLL_KEY).getAsJsonPrimitive().isBoolean()) {
                // The boolean this setting first shipped as: true meant "reroll per copy".
                if (cellObj.get(LEGACY_REROLL_KEY).getAsBoolean()) roll = VariantCopyRoll.VARY;
            }
            // Read after the legacy key so an explicit "roll" always wins over it.
            if (cellObj.has(ROLL_KEY) && cellObj.get(ROLL_KEY).isJsonPrimitive()
                && cellObj.get(ROLL_KEY).getAsJsonPrimitive().isString()) {
                roll = VariantCopyRoll.parse(cellObj.get(ROLL_KEY).getAsString());
            }
            if (cellObj.has(SCOPE_KEY) && cellObj.get(SCOPE_KEY).isJsonPrimitive()
                && cellObj.get(SCOPE_KEY).getAsJsonPrimitive().isString()) {
                // parse() is total — an unrecognised word reads as "both", which stamps the room
                // the way it always did rather than dropping the cell.
                scope = VariantCopyScope.parse(cellObj.get(SCOPE_KEY).getAsString());
            }
            span = parseSpan(cellObj);
        } else {
            LOGGER.warn("[DungeonTrain] Variant sidecar {}: value for {} is neither array nor object, skipping.",
                contextId, contextPos);
            return null;
        }
        List<VariantState> states = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            VariantState parsed = parseVariantElement(el, blocks, contextId, contextPos);
            if (parsed != null) states.add(parsed);
        }
        return new ParsedCell(states, lockId, roll, scope, span);
    }

    /** The cell object's {@link #SPAN_KEY}, or {@link VariantSpan#NONE} when absent / malformed. */
    static VariantSpan parseSpan(JsonObject cellObj) {
        if (cellObj.has(SPAN_KEY) && cellObj.get(SPAN_KEY).isJsonPrimitive()
            && cellObj.get(SPAN_KEY).getAsJsonPrimitive().isString()) {
            return VariantSpan.fromToken(cellObj.get(SPAN_KEY).getAsString());
        }
        return VariantSpan.NONE;
    }

    /**
     * Append a cell value to a JSON buffer in the form chosen by
     * {@code lockId}: bare-array (v3 shape) when {@code lockId == 0}, or
     * cell-object {@code {lockId, states}} when {@code lockId > 0}.
     * Used by all four sidecars to keep on-disk output identical.
     */
    public static void appendCellJson(StringBuilder sb, List<VariantState> states, int lockId) {
        appendCellJson(sb, states, lockId, VariantCopyRoll.DEFAULT);
    }

    /**
     * {@link #appendCellJson(StringBuilder, List, int)} plus the v10 per-copy
     * reroll flag. A cell with the flag set takes the object form even when it
     * has no lock-id — the flag has nowhere else to live — and a cell without
     * it writes exactly what it always did, so every sidecar authored before
     * v10 re-saves byte-identical.
     */
    public static void appendCellJson(StringBuilder sb, List<VariantState> states, int lockId,
                                      VariantCopyRoll roll) {
        appendCellJson(sb, states, lockId, roll, VariantCopyScope.BOTH);
    }

    /**
     * {@link #appendCellJson(StringBuilder, List, int, VariantCopyRoll)} plus the
     * v10 {@link VariantCopyScope}. Both v10 fields behave the same way: they
     * force the object form when set, because they have nowhere else to live,
     * and are omitted entirely at their defaults so a cell that never used them
     * writes exactly what it always wrote.
     */
    public static void appendCellJson(StringBuilder sb, List<VariantState> states, int lockId,
                                      VariantCopyRoll roll, VariantCopyScope scope) {
        appendCellJson(sb, states, lockId, roll, scope, VariantSpan.NONE);
    }

    /**
     * The full cell writer: the v10 fields plus the per-cell {@link VariantSpan}, which follows
     * the same rule — object form when set, omitted at its {@code AUTO} default.
     */
    public static void appendCellJson(StringBuilder sb, List<VariantState> states, int lockId,
                                      VariantCopyRoll roll, VariantCopyScope scope, VariantSpan span) {
        if (roll == null) roll = VariantCopyRoll.DEFAULT;
        if (scope == null) scope = VariantCopyScope.BOTH;
        if (span == null) span = VariantSpan.NONE;
        if (lockId > 0 || !roll.isDefault() || !scope.isDefault() || !span.isDefault()) {
            sb.append("{ ");
            if (lockId > 0) sb.append("\"lockId\": ").append(lockId).append(", ");
            if (!roll.isDefault()) {
                sb.append('"').append(ROLL_KEY).append("\": \"").append(roll.id()).append("\", ");
            }
            if (!scope.isDefault()) {
                sb.append('"').append(SCOPE_KEY).append("\": \"").append(scope.id()).append("\", ");
            }
            if (!span.isDefault()) {
                sb.append('"').append(SPAN_KEY).append("\": \"").append(span.toToken()).append("\", ");
            }
            sb.append("\"states\": [");
            boolean firstState = true;
            for (VariantState s : states) {
                if (!firstState) sb.append(", ");
                appendVariantJson(sb, s);
                firstState = false;
            }
            sb.append("] }");
        } else {
            sb.append("[");
            boolean firstState = true;
            for (VariantState s : states) {
                if (!firstState) sb.append(", ");
                appendVariantJson(sb, s);
                firstState = false;
            }
            sb.append("]");
        }
    }

    public static void appendVariantJson(StringBuilder sb, VariantState s) {
        if (s.isMob()) {
            // v7 mob entry — "entity" instead of "state". The state field is
            // always the empty-placeholder sentinel (forced by the canonical
            // constructor) so omit it from the JSON; the reader knows to
            // infer it.
            sb.append("{\"entity\": \"").append(escapeJson(s.entityId().toString())).append("\"");
            if (s.hasBlockEntityData()) {
                sb.append(", \"nbt\": \"").append(escapeJson(s.blockEntityNbt().toString())).append("\"");
            }
            if (s.weight() != 1) {
                sb.append(", \"weight\": ").append(s.weight());
            }
            if (!s.rotation().isDefault()) {
                sb.append(", \"rotation\": ");
                appendRotationJson(sb, s.rotation(), false);
            }
            // Armor-stand / entity entries can carry an equipment loadout link
            // (e.g. minecraft:armor_stand + lootPrefab); emit it so the link
            // survives the round-trip. Mirrors the block branch below.
            if (s.linkedLootPrefabId() != null) {
                sb.append(", \"lootPrefab\": \"").append(escapeJson(s.linkedLootPrefabId())).append("\"");
            }
            if (!s.half().isDefault()) {
                sb.append(", \"half\": \"").append(halfModeName(s.half().mode())).append("\"");
            }
            if (!s.difficulty().isDefault()) {
                appendDifficultyJson(sb, s.difficulty());
            }
            if (!s.active().isDefault()) {
                sb.append(", \"active\": \"").append(activeModeName(s.active().mode())).append("\"");
            }
            appendConditionJson(sb, s.condition());
            sb.append("}");
            return;
        }
        String stateStr = BlockStateParser.serialize(s.state());
        if (s.isPlainBareString()) {
            sb.append('"').append(escapeJson(stateStr)).append('"');
            return;
        }
        // Object form — emit only the fields that differ from the default
        // (state always; nbt / weight / rotation only when set) so v1/v2
        // entries that gain a non-default value still produce the smallest
        // possible diff.
        sb.append("{\"state\": \"").append(escapeJson(stateStr)).append("\"");
        if (s.hasBlockEntityData()) {
            // CompoundTag.toString() returns the canonical SNBT representation —
            // round-trips with TagParser.parseTag on the read side.
            sb.append(", \"nbt\": \"").append(escapeJson(s.blockEntityNbt().toString())).append("\"");
        }
        if (s.weight() != 1) {
            sb.append(", \"weight\": ").append(s.weight());
        }
        if (!s.rotation().isDefault()) {
            sb.append(", \"rotation\": ");
            appendRotationJson(sb, s.rotation(), RotationApplier.isCompass(s.state()));
        }
        if (s.linkedLootPrefabId() != null) {
            sb.append(", \"lootPrefab\": \"").append(escapeJson(s.linkedLootPrefabId())).append("\"");
        }
        if (!s.half().isDefault()) {
            sb.append(", \"half\": \"").append(halfModeName(s.half().mode())).append("\"");
        }
        if (s.isGroupRef()) {
            sb.append(", \"groupRef\": ").append(s.groupRef());
            if (!s.groupStates().isEmpty()) {
                // The group's options, so the reference still resolves once no cell carries the id.
                sb.append(", \"groupStates\": [");
                for (int i = 0; i < s.groupStates().size(); i++) {
                    if (i > 0) sb.append(", ");
                    appendVariantJson(sb, s.groupStates().get(i));
                }
                sb.append("]");
            }
        }
        if (!s.active().isDefault()) {
            sb.append(", \"active\": \"").append(activeModeName(s.active().mode())).append("\"");
        }
        if (!s.connect().isDefault()) {
            sb.append(", \"connect\": \"").append(s.connect().id()).append("\"");
        }
        if (!s.growth().isDefault()) {
            sb.append(", \"growth\": \"").append(s.growth().id()).append("\"");
        }
        if (!s.painting().isDefault()) {
            sb.append(", \"painting\": \"").append(s.painting().id()).append("\"");
        }
        if (!s.hung().isDefault()) {
            sb.append(", \"hung\": \"").append(s.hung().id()).append("\"");
        }
        appendConditionJson(sb, s.condition());
        sb.append("}");
    }

    /** Emit {@code , "if": "<token>"} for a set condition; nothing for none. */
    private static void appendConditionJson(StringBuilder sb, VariantCondition c) {
        if (c.isDefault()) return;
        sb.append(", \"if\": \"").append(escapeJson(c.id())).append("\"");
    }

    /**
     * Parse the additive {@code "if"} token. Absent → {@link VariantCondition#NONE}; a token whose
     * target is malformed or whose every block id is unknown also lands on none, with a warning so
     * an author can see why a gated row became unconditional.
     */
    private static VariantCondition parseCondition(JsonElement el, String contextId, BlockPos contextPos) {
        if (el == null || !el.isJsonPrimitive()) return VariantCondition.NONE;
        String raw = el.getAsString();
        VariantCondition c = VariantCondition.parse(raw);
        if (c.isDefault() && !raw.isBlank()) {
            LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: condition '{}' has no usable target or block, dropping it.",
                contextId, contextPos, raw);
        }
        return c;
    }

    /**
     * Parse a v5 {@code "rotation"} element ({@code {"mode": "...", "dirs": ["..."]}}).
     * Returns {@link VariantRotation#NONE} if the element is missing, null,
     * or malformed. The {@link VariantRotation} canonical constructor
     * silently re-clamps malformed mode/mask combinations.
     *
     * <p>{@code compass} says the entry's block is a {@link RotationApplier#isCompass}
     * block: only {@code "compass"} is read then, and a legacy {@code "dirs"} is
     * ignored. Heads captured before compass rotation often inherited a 6-way
     * lock from a neighbouring block, which had no effect; read as compass
     * slots it would turn them (NORTH's bit is slot 2, west). Other blocks read
     * only {@code "dirs"}.</p>
     */
    static VariantRotation parseRotation(JsonElement el, boolean compass, String contextId, BlockPos contextPos) {
        if (el == null || !el.isJsonObject()) return VariantRotation.NONE;
        JsonObject obj = el.getAsJsonObject();
        VariantRotation.Mode mode = VariantRotation.Mode.RANDOM;
        if (obj.has("mode") && obj.get("mode").isJsonPrimitive()) {
            String raw = obj.get("mode").getAsString();
            try {
                mode = VariantRotation.Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown rotation mode '{}', defaulting to random.",
                    contextId, contextPos, raw);
            }
        }
        int dirMask = 0;
        if (!compass && obj.has("dirs") && obj.get("dirs").isJsonArray()) {
            for (JsonElement d : obj.getAsJsonArray("dirs")) {
                if (!d.isJsonPrimitive()) continue;
                String name = d.getAsString().trim().toUpperCase(Locale.ROOT);
                try {
                    net.minecraft.core.Direction dir = net.minecraft.core.Direction.valueOf(name);
                    dirMask |= VariantRotation.maskOf(dir);
                } catch (IllegalArgumentException ignored) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown rotation dir '{}', skipping.",
                        contextId, contextPos, name);
                }
            }
        }
        if (compass && obj.has("compass") && obj.get("compass").isJsonArray()) {
            for (JsonElement c : obj.getAsJsonArray("compass")) {
                if (!c.isJsonPrimitive()) continue;
                int slot = RotationApplier.compassSlotOf(c.getAsString());
                if (slot < 0) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown compass point '{}', skipping.",
                        contextId, contextPos, c.getAsString());
                    continue;
                }
                dirMask |= 1 << slot;
            }
        }
        return new VariantRotation(mode, dirMask);
    }

    /**
     * Parse an optional {@code "half"} string ({@code "top"} / {@code "random"} /
     * {@code "bottom"}). Returns {@code null} when the field is missing or
     * malformed — callers apply the v7 read-time migration that derives the
     * value from the captured state's {@code SLAB_TYPE} / {@code HALF}
     * property and the existing rotation mode.
     */
    private static VariantHalf parseHalf(JsonElement el, String contextId, BlockPos contextPos) {
        if (el == null || !el.isJsonPrimitive()) return null;
        String raw = el.getAsString().trim();
        if (raw.isEmpty()) return null;
        try {
            return new VariantHalf(VariantHalf.Mode.valueOf(raw.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown half mode '{}', defaulting to random.",
                contextId, contextPos, raw);
            return VariantHalf.NONE;
        }
    }

    /**
     * Parse an optional {@code "active"} string ({@code "active"} / {@code "random"} /
     * {@code "inactive"}). Returns {@code null} when the field is missing so the
     * caller can derive the mode from the captured state instead
     * ({@link #migrateActiveFromState}); a malformed value logs and falls back
     * to the default.
     */
    /**
     * Parse an optional {@code "connect"} string ({@code "auto"} / {@code "on"} /
     * {@code "off"}). Absent → Default; unrecognised → Default with a warning.
     */
    private static VariantConnect.Mode parseConnect(JsonElement el, String contextId, BlockPos contextPos) {
        if (el == null || el.isJsonNull()) return VariantConnect.Mode.DEFAULT;
        String token = el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
        VariantConnect.Mode mode = VariantConnect.Mode.parse(token);
        if (mode == null) {
            LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown connect '{}', using default.",
                contextId, contextPos, el);
            return VariantConnect.Mode.DEFAULT;
        }
        return mode;
    }

    /**
     * Parse an optional {@code "growth"} token ({@code "up 2-5"}, {@code "down 1-8 notip"}).
     * Absent → off; malformed → off with a warning.
     */
    private static VariantGrowth parseGrowth(JsonElement el, String contextId, BlockPos contextPos) {
        if (el == null || el.isJsonNull()) return VariantGrowth.NONE;
        String token = el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
        VariantGrowth growth = VariantGrowth.parse(token);
        if (growth == null) {
            LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown growth '{}', using off.",
                contextId, contextPos, el);
            return VariantGrowth.NONE;
        }
        return growth;
    }

    /**
     * Parse an optional {@code "painting"} token ({@code "any random"}, {@code "2x1 minecraft:kebab"}).
     * Absent → off; malformed → off with a warning.
     */
    private static VariantPainting parsePainting(JsonElement el, String contextId, BlockPos contextPos) {
        if (el == null || el.isJsonNull()) return VariantPainting.NONE;
        String token = el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
        VariantPainting painting = token == null ? null : VariantPainting.parse(token);
        if (painting == null) {
            LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown painting '{}', using off.",
                contextId, contextPos, el);
            return VariantPainting.NONE;
        }
        return painting;
    }

    private static VariantActive parseActive(JsonElement el, String contextId, BlockPos contextPos) {
        if (el == null || !el.isJsonPrimitive()) return null;
        String raw = el.getAsString().trim();
        if (raw.isEmpty()) return null;
        try {
            return new VariantActive(VariantActive.Mode.valueOf(raw.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown active mode '{}', defaulting to inactive.",
                contextId, contextPos, raw);
            return VariantActive.NONE;
        }
    }

    /**
     * Read-time migration for entries that predate the {@code active} field:
     * a state whose redstone toggle is already {@code true} (a hand-edited
     * {@code open=true}, or a world-block capture of an opened trapdoor) is
     * {@link VariantActive.Mode#ACTIVE}; everything else is the default.
     */
    static VariantActive migrateActiveFromState(BlockState state) {
        return VariantActive.fromState(state);
    }

    /**
     * Parse an optional {@code "difficulty"} object ({@code {"min": N, "max": N|"all"}})
     * for mob entries. Returns {@link VariantDifficulty#NONE} when the field is
     * missing or malformed. {@code max} accepts a number or the string
     * {@code "all"} ({@link VariantDifficulty#ALL} — no upper bound). The
     * {@link VariantDifficulty} canonical constructor re-clamps the range.
     */
    private static VariantDifficulty parseDifficulty(JsonElement el, String contextId, BlockPos contextPos) {
        if (el == null || !el.isJsonObject()) return VariantDifficulty.NONE;
        JsonObject obj = el.getAsJsonObject();
        int min = 0;
        if (obj.has("min") && obj.get("min").isJsonPrimitive()
            && obj.get("min").getAsJsonPrimitive().isNumber()) {
            min = obj.get("min").getAsInt();
        }
        int max = VariantDifficulty.ALL;
        if (obj.has("max") && obj.get("max").isJsonPrimitive()) {
            if (obj.get("max").getAsJsonPrimitive().isNumber()) {
                max = obj.get("max").getAsInt();
            } else if (obj.get("max").getAsJsonPrimitive().isString()) {
                String raw = obj.get("max").getAsString().trim();
                if (!raw.equalsIgnoreCase("all")) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown difficulty max '{}', treating as all.",
                        contextId, contextPos, raw);
                }
                max = VariantDifficulty.ALL;
            }
        }
        return new VariantDifficulty(min, max);
    }

    /** Emit a non-default {@code "difficulty"} object; {@code max} writes {@code "all"} for the sentinel. */
    private static void appendDifficultyJson(StringBuilder sb, VariantDifficulty d) {
        sb.append(", \"difficulty\": {\"min\": ").append(d.min()).append(", \"max\": ");
        if (d.max() == VariantDifficulty.ALL) {
            sb.append("\"all\"");
        } else {
            sb.append(d.max());
        }
        sb.append("}");
    }

    /**
     * Derive a {@link VariantHalf} for a v7 file that predates the
     * {@code "half"} field. Mirrors the historical {@code randomizeFlip}
     * gating so existing prefabs spawn identically to before:
     * <ul>
     *   <li>{@code rotMode == RANDOM} → {@code RANDOM} (today's
     *       randomizeFlip fires).</li>
     *   <li>{@code rotMode == LOCK} → read the captured state's
     *       {@code SLAB_TYPE} / {@code HALF}; TOP → {@code TOP}, otherwise
     *       {@code BOTTOM}. Preserves the captured upside-down-ness.</li>
     *   <li>{@code rotMode == OPTIONS} → {@code RANDOM} (OPTIONS didn't
     *       fire randomizeFlip historically; no clean preserve-captured
     *       semantic to migrate, so default to the safer roll).</li>
     * </ul>
     * Returns {@link VariantHalf#NONE} for blocks without SLAB_TYPE/HALF —
     * the value is a no-op at spawn time for those.
     */
    private static VariantHalf migrateHalfFromRotation(BlockState captured, VariantRotation rotation) {
        if (captured == null) return VariantHalf.NONE;
        boolean hasHalf = captured.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF);
        boolean hasSlab = captured.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE);
        if (!hasHalf && !hasSlab) return VariantHalf.NONE;
        if (rotation.mode() != VariantRotation.Mode.LOCK) return VariantHalf.NONE;
        boolean top;
        if (hasHalf) {
            top = captured.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
                == net.minecraft.world.level.block.state.properties.Half.TOP;
        } else {
            top = captured.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)
                == net.minecraft.world.level.block.state.properties.SlabType.TOP;
        }
        return top ? VariantHalf.top() : VariantHalf.bottom();
    }

    /** Lowercase enum name for the {@code "half"} JSON field. */
    private static String halfModeName(VariantHalf.Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private static String activeModeName(VariantActive.Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Serialise a {@link VariantRotation} to the v5 JSON shape. Direction
     * names are written lowercase for human readability; the parser is
     * case-insensitive. A {@link RotationApplier#isCompass} block's mask is
     * compass slots, written as {@code "compass": ["n", "ne", ...]} so it never
     * collides with the {@code "dirs"} meaning.
     */
    static void appendRotationJson(StringBuilder sb, VariantRotation rot, boolean compass) {
        sb.append("{\"mode\": \"").append(rot.mode().name().toLowerCase(Locale.ROOT)).append("\"");
        if (compass && rot.dirMask() != 0) {
            sb.append(", \"compass\": [");
            boolean first = true;
            for (int slot = 0; slot < RotationApplier.COMPASS_SLOTS; slot++) {
                if ((rot.dirMask() & (1 << slot)) == 0) continue;
                if (!first) sb.append(", ");
                sb.append('"').append(RotationApplier.compassJsonName(slot)).append('"');
                first = false;
            }
            sb.append("]");
        } else if (rot.dirMask() != 0) {
            sb.append(", \"dirs\": [");
            boolean first = true;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                if ((rot.dirMask() & VariantRotation.maskOf(d)) == 0) continue;
                if (!first) sb.append(", ");
                sb.append('"').append(d.name().toLowerCase(Locale.ROOT)).append('"');
                first = false;
            }
            sb.append("]");
        }
        sb.append("}");
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
