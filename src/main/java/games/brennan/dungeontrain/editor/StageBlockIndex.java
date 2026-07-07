package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side index of what a {@link games.brennan.dungeontrain.template.Stage} <b>uses</b>:
 * which parts are explicitly linked to it (tier-1 {@code WeightedName.stageId} links across every
 * carriage template's {@code .parts.json} — no gate-overlap fallback), and which unique blocks
 * those parts are built from (structural NBT palette ∪ variants-sidecar candidates, deduped per
 * {@link net.minecraft.world.level.block.Block} registry id).
 *
 * <p>Feeds the Stage Blocks panel, the Stages-panel row icon strips, {@link StageDuplicator}
 * (which parts + which assignment entries to copy), {@link StageBlockReplacer} (which parts to
 * rewrite), and the {@link EditorPartsStageFilter} grid pass.</p>
 *
 * <p><b>Caching:</b> block aggregation is compute-on-demand into {@link #CACHE} — every input is
 * already cached by {@link CarriagePartTemplateStore}, {@link CarriagePartVariantBlocks}, and
 * {@link CarriageVariantPartsStore}, so a recompute is cheap, and those stores invalidate this
 * whole index (one {@link #invalidateAll()} line each) on any save/delete. {@link #generation()}
 * moves with every invalidation so per-tick callers (the menu-snapshot dedup key) can detect "the
 * strips may have changed" without recomputing anything.</p>
 *
 * <p>Palette access reflects the private {@code StructureTemplate.palettes} field — the repo does
 * not ship an access transformer; same lazy cached-{@link Field} pattern and log-and-degrade
 * failure mode as {@code TrackTemplateStore.extractCells}.</p>
 */
public final class StageBlockIndex {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Compound key for a part referenced by a stage. Name is canonicalised to lowercase. */
    public record PartRef(CarriagePartKind kind, String name) {
        public PartRef {
            name = name == null ? "" : name.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A block and its usage tally, rounded to a whole number for display. Usage weights structural
     * placements as 1 each and variant candidates fractionally by weight (see {@link #compute}).
     */
    public record BlockUse(String blockId, int count) {}

    /** Unique blocks for one part — usage-ordered (most-used first). */
    public record PartBlocks(PartRef part, List<BlockUse> uses) {
        /** Block ids in usage order — for the row / per-part icon strips. */
        public List<String> blockIds() {
            List<String> ids = new ArrayList<>(uses.size());
            for (BlockUse u : uses) ids.add(u.blockId());
            return ids;
        }
    }

    /** Per-stage aggregation: parts (stable resolution order) + the usage-ordered union of blocks. */
    public record StageBlocks(String stageId, List<PartBlocks> parts, List<BlockUse> aggregated) {
        /** Aggregated block ids in usage order — for the Stages-panel row strips. */
        public List<String> aggregatedBlockIds() {
            List<String> ids = new ArrayList<>(aggregated.size());
            for (BlockUse u : aggregated) ids.add(u.blockId());
            return ids;
        }
    }

    /** Keyed on lowercased stage id. Cleared wholesale by {@link #invalidateAll()}. */
    private static final Map<String, StageBlocks> CACHE = new ConcurrentHashMap<>();

    /** Bumped on every invalidation — cheap change signal for per-tick snapshot dedup keys. */
    private static final AtomicLong GENERATION = new AtomicLong();

    /** Reflected accessor for {@link StructureTemplate#palettes}. Private in vanilla; no AT shipped. */
    private static volatile Field palettesField;

    private StageBlockIndex() {}

    /** Monotonic change counter — moves whenever any underlying store mutates. */
    public static long generation() {
        return GENERATION.get();
    }

    /** Drop every cached aggregation and advance {@link #generation()}. Called by the store hooks. */
    public static void invalidateAll() {
        CACHE.clear();
        GENERATION.incrementAndGet();
    }

    /**
     * The parts explicitly linked to {@code stageId} (tier-1 {@code stageId} match,
     * case-insensitive) across every carriage variant's {@code .parts.json}, deduped, in stable
     * order (variant-registry order, then kind ordinal, then entry order). The {@code "none"}
     * sentinel never appears.
     */
    public static List<PartRef> partsForStage(String stageId) {
        if (stageId == null || stageId.isBlank()) return List.of();
        String key = stageId.toLowerCase(Locale.ROOT);
        Set<PartRef> out = new LinkedHashSet<>();
        for (CarriageVariant variant : CarriageVariantRegistry.allVariants()) {
            Optional<CarriagePartAssignment> assignment = CarriageVariantPartsStore.get(variant);
            if (assignment.isEmpty()) continue;
            for (CarriagePartKind kind : CarriagePartKind.values()) {
                for (CarriagePartAssignment.WeightedName e : assignment.get().entries(kind)) {
                    if (matchesStage(e, key)) out.add(new PartRef(kind, e.name()));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * The raw assignment entries linked to {@code stageId}, grouped
     * {@code variantId → kind → entries} — the feed for {@link StageDuplicator}, which needs each
     * entry's weight/sideMode/endMode/inline-gate to clone it faithfully. Insertion order follows
     * the same stable resolution order as {@link #partsForStage}.
     */
    public static Map<String, EnumMap<CarriagePartKind, List<CarriagePartAssignment.WeightedName>>>
            entriesForStage(String stageId) {
        Map<String, EnumMap<CarriagePartKind, List<CarriagePartAssignment.WeightedName>>> out =
            new LinkedHashMap<>();
        if (stageId == null || stageId.isBlank()) return out;
        String key = stageId.toLowerCase(Locale.ROOT);
        for (CarriageVariant variant : CarriageVariantRegistry.allVariants()) {
            Optional<CarriagePartAssignment> assignment = CarriageVariantPartsStore.get(variant);
            if (assignment.isEmpty()) continue;
            for (CarriagePartKind kind : CarriagePartKind.values()) {
                for (CarriagePartAssignment.WeightedName e : assignment.get().entries(kind)) {
                    if (!matchesStage(e, key)) continue;
                    out.computeIfAbsent(variant.id(), v -> new EnumMap<>(CarriagePartKind.class))
                       .computeIfAbsent(kind, k -> new ArrayList<>())
                       .add(e);
                }
            }
        }
        return out;
    }

    /** True when {@code e} is a real part explicitly linked to the (lowercased) stage {@code key}. */
    private static boolean matchesStage(CarriagePartAssignment.WeightedName e, String key) {
        return e.stageId() != null
            && e.stageId().toLowerCase(Locale.ROOT).equals(key)
            && !CarriagePartKind.NONE.equals(e.name());
    }

    /**
     * Per-part and aggregated unique block ids for {@code stageId}. Cached; recomputed only after
     * an {@link #invalidateAll()}.
     */
    public static StageBlocks blocksForStage(ServerLevel level, String stageId) {
        if (stageId == null || stageId.isBlank()) {
            return new StageBlocks("", List.of(), List.of());
        }
        String key = stageId.toLowerCase(Locale.ROOT);
        StageBlocks cached = CACHE.get(key);
        if (cached != null) return cached;
        StageBlocks computed = compute(level, key);
        CACHE.put(key, computed);
        return computed;
    }

    /**
     * Aggregated block-id strip for <b>every</b> stage — the Stages-panel row icons. Reads through
     * the same cache as {@link #blocksForStage}; combine with {@link #generation()} in per-tick
     * dedup keys so steady-state ticks never recompute.
     */
    public static Map<String, List<String>> blockStripForAllStages(ServerLevel level) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String id : StageStore.allIds()) {
            out.put(id, blocksForStage(level, id).aggregatedBlockIds());
        }
        return out;
    }

    private static StageBlocks compute(ServerLevel level, String key) {
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        List<PartBlocks> parts = new ArrayList<>();
        Map<String, Double> stageScore = new HashMap<>();
        for (PartRef ref : partsForStage(key)) {
            Map<String, Double> partScore = new HashMap<>();
            scorePart(level, ref, dims, partScore);
            parts.add(new PartBlocks(ref, toOrderedUses(partScore)));
            partScore.forEach((id, s) -> stageScore.merge(id, s, Double::sum));
        }
        return new StageBlocks(key, List.copyOf(parts), toOrderedUses(stageScore));
    }

    /**
     * Accumulate a usage score per block for one part (mirrors {@code TemplateBlocksMenuController.
     * buildBlockList}'s base-vs-variant split, but weighted and read from the part files):
     * <ul>
     *   <li>Structural palette positions <b>without</b> a variant cell → {@code +1.0} per placement.</li>
     *   <li>Variant cells → each candidate contributes {@code weight / cellTotalWeight} (a low-weight
     *       alternative counts for a fraction of a use), so nothing is double-counted with the base.</li>
     * </ul>
     */
    private static void scorePart(ServerLevel level, PartRef ref, CarriageDims dims,
                                  Map<String, Double> into) {
        CarriagePartVariantBlocks sidecar =
            CarriagePartVariantBlocks.loadFor(ref.kind(), ref.name(), ref.kind().dims(dims));

        // Flagged (variant-cell) local positions — skipped in the structural pass below.
        Set<BlockPos> flagged = new java.util.HashSet<>();
        for (CarriageVariantBlocks.Entry entry : sidecar.entries()) {
            flagged.add(entry.localPos());
        }

        // Structural base — one use per non-variant, non-air palette position.
        Optional<StructureTemplate> template =
            CarriagePartTemplateStore.get(level, ref.kind(), ref.name(), dims);
        if (template.isPresent()) {
            for (StructureTemplate.Palette palette : palettesOf(template.get())) {
                for (StructureTemplate.StructureBlockInfo info : palette.blocks()) {
                    if (flagged.contains(info.pos())) continue;
                    String id = blockId(info.state());
                    if (id != null) into.merge(id, 1.0, Double::sum);
                }
            }
        }

        // Variant candidates — fractional by weight within each cell.
        for (CarriageVariantBlocks.Entry entry : sidecar.entries()) {
            int total = 0;
            for (VariantState s : entry.states()) {
                if (!s.isMob() && blockId(s.state()) != null) total += s.weight();
            }
            if (total <= 0) continue;
            for (VariantState s : entry.states()) {
                if (s.isMob()) continue;
                String id = blockId(s.state());
                if (id != null) into.merge(id, (double) s.weight() / total, Double::sum);
            }
        }
    }

    /** Order a score map most-used first (tie-break block id asc), rounding the score for display. */
    private static List<BlockUse> toOrderedUses(Map<String, Double> score) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(score.entrySet());
        entries.sort((a, b) -> {
            int byScore = Double.compare(b.getValue(), a.getValue());
            return byScore != 0 ? byScore : a.getKey().compareTo(b.getKey());
        });
        List<BlockUse> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, Double> e : entries) {
            out.add(new BlockUse(e.getKey(), Math.max(1, (int) Math.round(e.getValue()))));
        }
        return List.copyOf(out);
    }

    /** Block registry id of {@code state}, or {@code null} for air / the empty-placeholder sentinel. */
    private static String blockId(BlockState state) {
        if (state == null || state.isAir()) return null;
        if (CarriageVariantBlocks.isEmptyPlaceholder(state)) return null;
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * Reflect {@code StructureTemplate.palettes}. Empty on reflective failure (mapping change on an
     * MC upgrade) — same log-and-degrade behaviour as {@code TrackTemplateStore.extractCells}.
     */
    @SuppressWarnings("unchecked")
    private static List<StructureTemplate.Palette> palettesOf(StructureTemplate template) {
        try {
            Field field = palettesField;
            if (field == null) {
                field = StructureTemplate.class.getDeclaredField("palettes");
                field.setAccessible(true);
                palettesField = field;
            }
            List<StructureTemplate.Palette> palettes =
                (List<StructureTemplate.Palette>) field.get(template);
            return palettes == null ? List.of() : palettes;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.error("[DungeonTrain] Unable to read template palettes (palettes field unreachable): {}",
                e.toString());
            return Collections.emptyList();
        }
    }
}
