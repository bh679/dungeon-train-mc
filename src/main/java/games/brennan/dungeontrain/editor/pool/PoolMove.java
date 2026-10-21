package games.brennan.dungeontrain.editor.pool;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.editor.EditorShipped;
import games.brennan.dungeontrain.editor.EditorStampedCategoryState;
import games.brennan.dungeontrain.editor.TemplateSizeStore;
import games.brennan.dungeontrain.editor.WholeCarriageEditor;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.editor.WholeHalfTemplateStore;
import games.brennan.dungeontrain.editor.WholeTemplateNew;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.ShellPool;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeKind;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Move a template between the carriage, whole and contents pools without changing its size tier.
 *
 * <p>Never reshapes: a shell and a whole carriage are the same box, so one file becomes the other; a
 * full box becomes contents by taking its interior; contents become a full box by being baked onto
 * the tier's default shell. A template whose saved file is not the tier's box is refused rather than
 * padded or cropped. The original is removed once the copy is in place, and what could not travel
 * with it is named in the result.</p>
 */
public final class PoolMove {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PoolMove() {}

    public enum Outcome {
        MOVED, SAME_POOL, NOT_FOUND, INVALID_NAME, NAME_TAKEN, NOT_MOVABLE, PROTECTED, UNSAVED_EDITS,
        BOX_MISMATCH, GROUP_SIZE_MISMATCH, NO_SHELL, FAILED
    }

    /** {@code landed} is set on {@link Outcome#MOVED}; {@code detail} carries a number or message for the chat line. */
    public record Result(Outcome outcome, PoolRef landed, List<String> dropped, String detail) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null, List.of(), "");
        }

        static Result of(Outcome outcome, String detail) {
            return new Result(outcome, null, List.of(), detail == null ? "" : detail);
        }

        public boolean moved() {
            return outcome == Outcome.MOVED;
        }
    }

    /**
     * The pure refusals — checked before anything on disk is read. Same pool, a bad or taken name.
     * The command and the picker both go through here so they refuse the same things.
     */
    public static Optional<Outcome> refusal(PoolRef from, Pool to, String targetId) {
        if (from.pool() == to) return Optional.of(Outcome.SAME_POOL);
        if (!CarriageVariant.NAME_PATTERN.matcher(targetId).matches()) return Optional.of(Outcome.INVALID_NAME);
        if (taken(to, from.tier(), targetId)) return Optional.of(Outcome.NAME_TAKEN);
        return Optional.empty();
    }

    /** Whether {@code pool} already has a template called {@code id} (any tier — ids are unique per pool). */
    public static boolean taken(Pool pool, PoolTier tier, String id) {
        return switch (pool) {
            case CARRIAGE -> CarriageVariantRegistry.find(id).isPresent() || CarriageTemplateStore.shipsId(id);
            case CONTENTS -> CarriageContentsRegistry.find(id).isPresent();
            case WHOLE -> {
                for (WholeKind kind : WholeKind.values()) {
                    if (WholeTemplateNew.ids(kind).contains(id)) yield true;
                }
                yield false;
            }
        };
    }

    /**
     * Move {@code from} into {@code to}, as {@code newId} when given, else under its own id.
     * Runs on the server thread; touches the world only where a category is on show.
     */
    public static Result move(ServerLevel level, PoolRef from, Pool to, String newId) {
        ServerLevel overworld = level.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        String targetId = (newId == null || newId.isBlank() ? from.id() : newId.trim()).toLowerCase(Locale.ROOT);
        PoolRef landing = from.in(to).named(targetId);

        Template source = from.template();
        if (source == null) return Result.of(Outcome.NOT_FOUND);
        Optional<Outcome> refused = refusal(from, to, targetId);
        if (refused.isPresent()) return Result.of(refused.get());
        // A shipped template never moves, dev checkout or not: its bundled copy would stay behind (a
        // built-in cannot leave its registry) while the move stripped its sidecars out of the source
        // tree. Save it under a name of your own and move that.
        if (source.isBuiltin() || EditorShipped.isShipped(source)) return Result.of(Outcome.PROTECTED);
        if (EditorDirtyCheck.unsavedModelIds(overworld, dims, from.dirtyCategoryId()).contains(from.id())) {
            return Result.of(Outcome.UNSAVED_EDITS);
        }
        int groupSize = WholeCarriageEditor.groupSize();
        Optional<CarriageDims> boxOpt = from.tier().box(dims, groupSize);
        if (boxOpt.isEmpty()) return Result.of(Outcome.BOX_MISMATCH, from.tier().name().toLowerCase(Locale.ROOT));
        CarriageDims box = boxOpt.get();

        boolean written = false;
        try {
            StructureTemplate saved = read(overworld, from, box, groupSize);
            if (saved == null) {
                return from.tier() == PoolTier.GROUP && from.pool() != Pool.CONTENTS
                    ? Result.of(Outcome.GROUP_SIZE_MISMATCH, Integer.toString(groupSize))
                    : Result.of(Outcome.BOX_MISMATCH, boxText(box));
            }
            StructureTemplate target = reframe(overworld, saved, from.pool(), to, from.tier(), box);
            if (target == NO_SHELL) return Result.of(Outcome.NO_SHELL, PoolScratch.defaultShellId(from.tier()));
            if (target == null) return Result.of(Outcome.BOX_MISMATCH, boxText(box));

            write(landing, target);
            written = true;
            List<String> dropped = PoolSidecars.carry(from, landing, box);
            remove(overworld, dims, from, source);
            show(overworld, dims, landing);
            LOGGER.info("[DungeonTrain] Pool move: {} '{}' ({}) -> {} '{}'{}", from.pool().key(), from.id(),
                from.tier().name().toLowerCase(Locale.ROOT), to.key(), targetId,
                dropped.isEmpty() ? "" : " (left behind: " + String.join(", ", dropped) + ")");
            return new Result(Outcome.MOVED, landing, dropped, "");
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] Pool move: {} '{}' -> {} failed", from.pool().key(), from.id(), to.key(), e);
            // The copy landed but the original could not be taken away: take the copy back out, so
            // a failed move never leaves the template in two pools with its weight counted twice.
            if (written) rollback(overworld, dims, landing);
            return Result.of(Outcome.FAILED, e.toString());
        }
    }

    /** Remove the destination copy of a move that failed after {@link #write}; a failure here is only logged. */
    private static void rollback(ServerLevel overworld, CarriageDims dims, PoolRef landing) {
        try {
            Template landed = landing.template();
            if (landed == null) return;
            remove(overworld, dims, landing, landed);
            LOGGER.info("[DungeonTrain] Pool move: rolled back the copy at {} '{}'", landing.pool().key(), landing.id());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] Pool move: could not roll back the copy at {} '{}' — it stands in both pools",
                landing.pool().key(), landing.id(), e);
        }
    }

    // ---- read ------------------------------------------------------------------------------------

    /** The saved template at {@code from}, gated to its tier's box; null when it is another shape. */
    private static StructureTemplate read(ServerLevel level, PoolRef from, CarriageDims box, int groupSize) {
        return switch (from.pool()) {
            case CARRIAGE -> CarriageVariantRegistry.find(from.id())
                .flatMap(v -> CarriageTemplateStore.get(level, v, box)).orElse(null);
            case CONTENTS -> CarriageContentsRegistry.find(from.id())
                .flatMap(c -> CarriageContentsStore.get(level, c, CarriageContentsPlacer.interiorSize(box))).orElse(null);
            case WHOLE -> switch (from.tier()) {
                case ROOM -> WholeCarriageTemplateStore.get(level, new WholeCarriage(from.id()), box).orElse(null);
                // The half store derives the half box from the world's dims itself.
                case HALF -> WholeHalfTemplateStore.get(level, new WholeCarriage(from.id()),
                    DungeonTrainWorldData.get(level).dims()).orElse(null);
                case GROUP -> CarriageGroupTemplateStore.get(level, new CarriageGroup(from.id()),
                    DungeonTrainWorldData.get(level).dims(), groupSize).orElse(null);
            };
        };
    }

    /** Sentinel for "the tier's default shell is missing" — distinct from a shape refusal. */
    private static final StructureTemplate NO_SHELL = new StructureTemplate();

    /** {@code saved} in the destination pool's frame: itself, its interior, or baked onto a shell. */
    private static StructureTemplate reframe(ServerLevel level, StructureTemplate saved, Pool fromPool, Pool to,
                                             PoolTier tier, CarriageDims box) {
        boolean fromFull = fromPool != Pool.CONTENTS;
        boolean toFull = to != Pool.CONTENTS;
        if (fromFull && toFull) return CarriagePlacer.sizeMatches(saved.getSize(), box) ? saved : null;
        if (fromFull) return PoolScratch.interiorOf(level, saved, box);
        Optional<StructureTemplate> shell = PoolScratch.defaultShell(level, tier, box);
        if (shell.isEmpty()) return NO_SHELL;
        return PoolScratch.bake(level, shell.get(), saved, box);
    }


    // ---- write / remove / show --------------------------------------------------------------------

    private static void write(PoolRef landing, StructureTemplate template) throws IOException {
        boolean dev = EditorDevMode.isEnabled();
        switch (landing.pool()) {
            case CARRIAGE -> {
                CarriageVariant.Custom v = landing.asShell();
                // The file lives in its pool's folder, so the pool is set before the save names the path.
                ShellPool.set(landing.id(), landing.tier().shellPool());
                try {
                    CarriageTemplateStore.save(v, template);
                    if (dev && CarriageTemplateStore.sourceTreeAvailable()) CarriageTemplateStore.saveToSource(v, template);
                } catch (IOException e) {
                    ShellPool.forget(landing.id());
                    throw e;
                }
                CarriageVariantRegistry.register(v);
            }
            case CONTENTS -> {
                CarriageContents.Custom c = landing.asContents();
                // The size names the row the plot lands in, so it is set before the registry places it.
                TemplateSizeStore.CONTENTS.set(landing.id(), landing.tier().contentsSize());
                try {
                    CarriageContentsStore.save(c, template);
                    if (dev && CarriageContentsStore.sourceTreeAvailable()) CarriageContentsStore.saveToSource(c, template);
                } catch (IOException e) {
                    try {
                        TemplateSizeStore.CONTENTS.forget(landing.id());
                    } catch (IOException forgetFailed) {
                        LOGGER.warn("[DungeonTrain] Pool move: size entry for '{}' could not be forgotten: {}",
                            landing.id(), forgetFailed.toString());
                    }
                    throw e;
                }
                CarriageContentsRegistry.register(c);
                // A template arriving in Contents starts in every carriage's Contents list, like a new one.
                CarriageContentsWeights.markNewOptIn(landing.id());
            }
            case WHOLE -> {
                WholeKind kind = landing.tier().wholeKind();
                switch (kind) {
                    case ROOM -> {
                        WholeCarriage w = new WholeCarriage(landing.id());
                        WholeCarriageTemplateStore.save(w, template);
                        if (dev && WholeCarriageTemplateStore.sourceTreeAvailable()) WholeCarriageTemplateStore.saveToSource(w, template);
                    }
                    case HALF -> {
                        WholeCarriage w = new WholeCarriage(landing.id());
                        WholeHalfTemplateStore.save(w, template);
                        if (dev && WholeHalfTemplateStore.sourceTreeAvailable()) WholeHalfTemplateStore.saveToSource(w, template);
                    }
                    case GROUP -> {
                        CarriageGroup g = new CarriageGroup(landing.id());
                        CarriageGroupTemplateStore.save(g, template);
                        if (dev && CarriageGroupTemplateStore.sourceTreeAvailable()) CarriageGroupTemplateStore.saveToSource(g, template);
                    }
                }
                WholeTemplateNew.register(kind, landing.id());
            }
        }
    }

    private static void remove(ServerLevel overworld, CarriageDims dims, PoolRef from, Template source) throws IOException {
        switch (from.pool()) {
            case CARRIAGE -> PoolDeletes.carriage(overworld, dims, ((Template.Carriage) source).variant(),
                EditorStampedCategoryState.isActive(EditorCategory.CARRIAGES));
            case CONTENTS -> PoolDeletes.contents(overworld, dims, ((Template.Contents) source).contents(),
                EditorStampedCategoryState.isActive(EditorCategory.CONTENTS));
            case WHOLE -> PoolDeletes.whole(overworld, dims, from.tier().wholeKind(), source,
                EditorStampedCategoryState.isActive(EditorCategory.WHOLE));
        }
    }

    /** Stamp the arrival's plot when its category is on show — it joins the end of its row. */
    private static void show(ServerLevel overworld, CarriageDims dims, PoolRef landing) {
        switch (landing.pool()) {
            case CARRIAGE -> {
                if (!EditorStampedCategoryState.isActive(EditorCategory.CARRIAGES)) return;
                CarriageStampGuard.run(() -> CarriageEditor.restampRowFrom(overworld, landing.id(), dims));
            }
            case CONTENTS -> {
                if (!EditorStampedCategoryState.isActive(EditorCategory.CONTENTS)) return;
                CarriageStampGuard.run(() -> CarriageContentsEditor.stampPlot(overworld, landing.asContents(), dims));
            }
            case WHOLE -> {
                if (!EditorStampedCategoryState.isActive(EditorCategory.WHOLE)) return;
                Template model = WholeTemplateNew.template(landing.tier().wholeKind(), landing.id());
                CarriageStampGuard.run(() -> WholeCarriageEditor.stampPlot(overworld, model, dims));
            }
        }
    }

    private static String boxText(CarriageDims box) {
        return box.length() + "×" + box.width() + "×" + box.height();
    }
}
