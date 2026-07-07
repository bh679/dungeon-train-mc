package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Duplicates a {@link Stage} <b>with its content set</b>: the Stage record itself (same gate, new
 * id), a copy of every part the stage uses (structural NBT + {@code .variants.json} sidecar, under
 * collision-safe new names), and a duplicated assignment entry in every carriage template's
 * {@code .parts.json} that referenced a copied part — the new entries keep the source entry's
 * weight / sideMode / endMode / inline gate but link to the new stage. The result is a fully
 * independent theme copy that can then be re-skinned via {@link StageBlockReplacer} without
 * touching the source stage.
 *
 * <p>Every write goes through the existing store save paths, so config-dir persistence and
 * dev-mode source-tree promotion are inherited. The Stage record is created <b>last</b> so a
 * mid-flight IO failure never leaves a stage whose content is missing.</p>
 *
 * <p>Known limitation (parity with {@link CarriagePartEditor#createFrom}'s duplicate flow):
 * container-contents sidecars (chest → loot-prefab links) are not copied.</p>
 */
public final class StageDuplicator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Longest stage id that still leaves ≥1 char for the base part name in every copy-name shape,
     * including the collision path {@code <base>_<id>_<n>} with a two-digit {@code n}:
     * {@code 1 + (1 + id) + (1 + 2) ≤ 32 ⇒ id ≤ 27}.
     */
    private static final int MAX_STAGE_ID_FOR_COPY_NAMES = 27;

    /** What a {@link #duplicate} run did — feed for chat feedback. */
    public record Result(String sourceStageId, String newStageId,
                         Map<StageBlockIndex.PartRef, String> partCopies,
                         List<StageBlockIndex.PartRef> skippedParts,
                         List<String> touchedVariantIds, int entriesAdded) {}

    private StageDuplicator() {}

    /**
     * Duplicate stage {@code sourceStageId} as {@code requestedNewId} (or {@code <src>_copy} when
     * null). Runs on the server thread.
     *
     * @throws IOException on unknown source, invalid/taken new id, or a config-dir write failure.
     *                     Source-tree promotion failures are warn-logged, never thrown.
     */
    public static Result duplicate(ServerLevel level, String sourceStageId,
                                   @Nullable String requestedNewId) throws IOException {
        Stage source = StageStore.get(sourceStageId)
            .orElseThrow(() -> new IOException("No such stage: " + sourceStageId));
        String newId = resolveNewStageId(source.id(), requestedNewId);
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        Map<String, EnumMap<CarriagePartKind, List<CarriagePartAssignment.WeightedName>>> byVariant =
            StageBlockIndex.entriesForStage(source.id());
        List<StageBlockIndex.PartRef> parts = StageBlockIndex.partsForStage(source.id());

        // 1. Copy each part's NBT + variants sidecar under a fresh name.
        Map<StageBlockIndex.PartRef, String> partCopies = new LinkedHashMap<>();
        List<StageBlockIndex.PartRef> skipped = new ArrayList<>();
        for (StageBlockIndex.PartRef ref : parts) {
            Optional<StructureTemplate> template =
                CarriagePartTemplateStore.get(level, ref.kind(), ref.name(), dims);
            if (template.isEmpty()) {
                // Missing / dims-mismatched template — don't duplicate entries that would point at
                // an empty copy.
                skipped.add(ref);
                continue;
            }
            String newName = copyPartName(ref.kind(), ref.name(), newId);
            copyPart(ref, newName, template.get(), dims);
            partCopies.put(ref, newName);
            CarriagePartRegistry.register(ref.kind(), newName);
        }

        // 2. Append duplicated assignment entries (stageId = the new stage) per carriage variant.
        List<String> touched = new ArrayList<>();
        int entriesAdded = 0;
        for (Map.Entry<String, EnumMap<CarriagePartKind, List<CarriagePartAssignment.WeightedName>>> ve
                : byVariant.entrySet()) {
            Optional<CarriageVariant> variant = CarriageVariantRegistry.find(ve.getKey());
            if (variant.isEmpty()) continue;
            CarriagePartAssignment assignment =
                CarriageVariantPartsStore.get(variant.get()).orElse(CarriagePartAssignment.EMPTY);
            boolean changed = false;
            for (Map.Entry<CarriagePartKind, List<CarriagePartAssignment.WeightedName>> ke
                    : ve.getValue().entrySet()) {
                CarriagePartKind kind = ke.getKey();
                List<CarriagePartAssignment.WeightedName> updated =
                    new ArrayList<>(assignment.entries(kind));
                for (CarriagePartAssignment.WeightedName e : ke.getValue()) {
                    String newName = partCopies.get(new StageBlockIndex.PartRef(kind, e.name()));
                    if (newName == null) continue;
                    updated.add(new CarriagePartAssignment.WeightedName(
                        newName, e.weight(), e.sideMode(), e.endMode(), e.gate(), newId));
                    entriesAdded++;
                    changed = true;
                }
                if (changed) assignment = assignment.with(kind, updated);
            }
            if (changed) {
                CarriageVariantPartsStore.save(variant.get(), assignment);
                touched.add(variant.get().id());
            }
        }

        // 3. Create the Stage record last — a mid-flight failure above never leaves a content-less
        // stage behind.
        StageStore.save(new Stage(newId, newId, source.gate()));

        // 4. Refresh the editor world (only meaningful while CARRIAGES is stamped).
        if (EditorStampedCategoryState.current().orElse(null) == EditorCategory.CARRIAGES) {
            for (Map.Entry<StageBlockIndex.PartRef, String> copy : partCopies.entrySet()) {
                // stampPlot is filter-aware via plotOrigin: with the stage filter on and the SOURCE
                // stage focused, the copies (linked to the NEW stage) have no slot yet and no-op —
                // they appear once the new stage is focused and the grid re-stamps.
                CarriagePartEditor.stampPlot(level, copy.getKey().kind(), copy.getValue(), dims);
            }
            if (EditorStageSelection.effective() != null) {
                CarriageEditor.stampAllPlots(level, dims);
            }
        }

        Result result = new Result(source.id(), newId, partCopies, skipped, touched, entriesAdded);
        LOGGER.info("[DungeonTrain] Stage duplicate: '{}' -> '{}' ({} part(s) copied, {} skipped, "
                + "{} entr(ies) added across {} template(s)).",
            source.id(), newId, partCopies.size(), skipped.size(), entriesAdded, touched.size());
        return result;
    }

    /** Copy one part's structural NBT, variants sidecar (incl. lock-ids + mirror flags). */
    private static void copyPart(StageBlockIndex.PartRef ref, String newName,
                                 StructureTemplate template, CarriageDims dims) throws IOException {
        CarriagePartKind kind = ref.kind();
        CarriagePartTemplateStore.save(kind, newName, template);
        if (EditorDevMode.isEnabled()) {
            try {
                CarriagePartTemplateStore.saveToSource(kind, newName, template);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Stage duplicate: source write failed for part {}:{}: {} "
                    + "(config write succeeded).", kind.id(), newName, e.toString());
            }
        }

        Vec3i partSize = kind.dims(dims);
        CarriagePartEditor.copyVariantSidecar(kind, ref.name(), newName, dims);
        // copyVariantSidecar carries entries + lock-ids but drops the editor mirror flags — copy
        // them separately when the source has any set (also covers an entry-less flagged sidecar).
        CarriagePartVariantBlocks sourceSidecar =
            CarriagePartVariantBlocks.loadFor(kind, ref.name(), partSize);
        if (sourceSidecar.mirrorX() || sourceSidecar.mirrorY() || sourceSidecar.mirrorZ()
                || sourceSidecar.mirrorVariants()) {
            CarriagePartVariantBlocks copySidecar =
                CarriagePartVariantBlocks.loadFor(kind, newName, partSize);
            copySidecar.setMirrorAxes(sourceSidecar.mirrorX(), sourceSidecar.mirrorY(),
                sourceSidecar.mirrorZ());
            copySidecar.setMirrorVariants(sourceSidecar.mirrorVariants());
            copySidecar.save(kind, newName);
        }
        if (EditorDevMode.isEnabled()) {
            try {
                CarriagePartVariantBlocks.loadFor(kind, newName, partSize).saveToSource(kind, newName);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Stage duplicate: sidecar source write failed for {}:{}: {} "
                    + "(config write succeeded).", kind.id(), newName, e.toString());
            }
        }
    }

    /**
     * Validate / derive the new stage id: {@code requested} when given, else {@code <src>_copy}
     * (numeric suffix on collision). Both branches must satisfy the shared {@code [a-z0-9_]{1,32}}
     * name contract — pre-contract installs may hold stage ids with {@code - . +} (created before
     * {@link StageStore#add} validated), and a derived {@code <src>_copy} inherits those chars, so
     * the derived id is checked too, not just the requested one.
     */
    static String resolveNewStageId(String sourceId, @Nullable String requested) throws IOException {
        if (requested != null && !requested.isBlank()) {
            String id = requested.trim().toLowerCase(Locale.ROOT);
            return validateNewStageId(id);
        }
        String base = truncate(sourceId, 32 - "_copy".length()) + "_copy";
        if (!StageStore.exists(base)) return validateNewStageId(base);
        for (int n = 2; n <= 99; n++) {
            String candidate = truncate(sourceId, 32 - "_copy".length() - Integer.toString(n).length())
                + "_copy" + n;
            if (!StageStore.exists(candidate)) return validateNewStageId(candidate);
        }
        throw new IOException("Cannot derive a free copy id from '" + sourceId + "' — pass one explicitly.");
    }

    private static String validateNewStageId(String id) throws IOException {
        if (!CarriageVariant.NAME_PATTERN.matcher(id).matches()) {
            throw new IOException("Invalid stage id '" + id + "' — use 1-32 of [a-z0-9_].");
        }
        if (StageStore.exists(id)) {
            throw new IOException("Stage '" + id + "' already exists.");
        }
        return id;
    }

    /**
     * Collision-safe copy name for a part: {@code <base>_<newStageId>}, base truncated into the
     * 32-char budget, numeric suffix when taken (registry, config file, or bundled resource).
     */
    static String copyPartName(CarriagePartKind kind, String baseName, String newStageId) throws IOException {
        if (newStageId.length() > MAX_STAGE_ID_FOR_COPY_NAMES) {
            throw new IOException("Stage id '" + newStageId + "' is too long to derive part copy "
                + "names — use " + MAX_STAGE_ID_FOR_COPY_NAMES + " chars or fewer.");
        }
        String suffix = "_" + newStageId;
        String candidate = truncate(baseName, 32 - suffix.length()) + suffix;
        for (int n = 2; n <= 99; n++) {
            if (!isTaken(kind, candidate)) return validateCopyName(kind, baseName, candidate);
            String num = "_" + n;
            candidate = truncate(baseName, 32 - suffix.length() - num.length()) + suffix + num;
        }
        if (!isTaken(kind, candidate)) return validateCopyName(kind, baseName, candidate);
        throw new IOException("Cannot allocate a copy name for part " + kind.id() + ":" + baseName + ".");
    }

    /**
     * Backstop against the part-name contract — a name {@link CarriagePartRegistry#register}
     * would silently reject must fail loudly here, BEFORE any file is written, not leave orphaned
     * NBT/sidecar copies plus {@code .parts.json} entries pointing at an unregistered part.
     */
    private static String validateCopyName(CarriagePartKind kind, String baseName, String candidate) throws IOException {
        if (!CarriagePartRegistry.NAME_PATTERN.matcher(candidate).matches()) {
            throw new IOException("Derived part copy name '" + candidate + "' (from " + kind.id() + ":"
                + baseName + ") violates the [a-z0-9_]{1,32} name contract.");
        }
        return candidate;
    }

    private static boolean isTaken(CarriagePartKind kind, String name) {
        return CarriagePartRegistry.isKnown(kind, name)
            || CarriagePartTemplateStore.exists(kind, name)
            || CarriagePartTemplateStore.bundled(kind, name);
    }

    private static String truncate(String s, int maxLen) {
        if (maxLen < 1) return s.substring(0, 1);
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
