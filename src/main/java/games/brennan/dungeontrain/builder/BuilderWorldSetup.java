package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.editor.EditorStageSelection;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.ship.sable.WorldgenForceGuard;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartPlacer;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the Train Builder world's contents once: the grass platform, a straight track across it,
 * and an empty train parked on the track.
 *
 * <p>This is deliberately <b>not</b> a worldgen feature. A {@code minecraft:flat} generator
 * discards the biome's feature list — {@code FlatLevelGeneratorSettings.adjustGenerationSettings()}
 * rebuilds generation settings from the flat config — so a biome modifier that works perfectly in
 * a noise world never fires in a superflat one. The first attempt at this did exactly that and
 * produced a world containing nothing but air.</p>
 *
 * <p>The carriages are stamped as ordinary world blocks via
 * {@link CarriagePlacer#placeAt(ServerLevel, BlockPos, CarriageVariant, CarriageDims)} — the same
 * call the editor uses for its previews. No Sable sub-level, no physics, nothing moving: a builder
 * wants a carriage that holds still, and plain blocks persist across a reload for free.</p>
 *
 * <p>Idempotent, keyed on the grass already being there, so reopening a builder world from the
 * world list is a no-op rather than a second train stamped on top of the first.</p>
 */
public final class BuilderWorldSetup {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();

    private BuilderWorldSetup() {}

    /**
     * @param mode which builder tile the player picked — decides how much train gets parked
     * @return true if the world was stamped, false if it was already set up
     */
    public static boolean setupIfNeeded(ServerLevel level, BuilderMode mode) {
        if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return false; // not a builder world — nothing to do
        }
        if (isAlreadySetUp(level)) {
            LOGGER.info("[DungeonTrain] Builder world already set up — skipping stamp");
            return false;
        }

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        long t0 = System.nanoTime();

        stampPlatform(level);
        stampTrack(level, dims);
        int carriages = mode.carriageCount();
        if (carriages > 0) {
            stampTrain(level, dims, carriages);
        }

        // Persist the mode: it was a title-screen click and lives nowhere else, so without this
        // a reopened builder world can't tell the client where the build bounds are.
        DungeonTrainWorldData.get(level).setBuilderMode(mode.id());
        DungeonTrainWorldData.get(level).setBuilderMirror(BuilderMirrorFlags.DEFAULT);

        LOGGER.info("[DungeonTrain] Builder world stamped for mode '{}' ({} carriage(s)) in {} ms",
                mode.id(), carriages, (System.nanoTime() - t0) / 1_000_000);
        return true;
    }

    /**
     * Re-shape an existing builder world for a different mode: strip the parked train and stamp
     * the new one. The platform and the track stay — every mode shares them, and rebuilding
     * 180 000 blocks to change the carriage count would be absurd.
     *
     * <p>Discards whatever was in the old carriages, so callers own the unsaved-changes
     * conversation (see {@code BuilderSwitchPacket}).</p>
     */
    public static void restamp(ServerLevel level, BuilderMode mode) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        int previous = BuilderMode.fromId(data.builderMode())
                .map(BuilderMode::carriageCount)
                .orElse(0);

        clearTrain(level, dims, previous);
        int carriages = mode.carriageCount();
        if (carriages > 0) {
            stampTrain(level, dims, carriages);
        }
        data.setBuilderMode(mode.id());
        LOGGER.info("[DungeonTrain] Builder world re-stamped: {} carriage(s) -> '{}' ({} carriage(s))",
                previous, mode.id(), carriages);
    }

    /**
     * Erase the previous train's footprint back to air — the full span including the flatbed pads,
     * from the train floor up through the carriage roof. Snapshots go with it: a baseline for a
     * carriage that no longer exists would make the next dirty check compare against a ghost.
     */
    private static void clearTrain(ServerLevel level, CarriageDims dims, int previousCarriages) {
        if (previousCarriages <= 0) {
            return;
        }
        int startX = BuilderWorldLayout.trainStartX(previousCarriages, dims);
        int endX = startX + BuilderWorldLayout.totalTrainLength(previousCarriages, dims) - 1;
        int topY = BuilderWorldLayout.TRAIN_Y + dims.height() - 1;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int cx = startX >> 4; cx <= endX >> 4; cx++) {
            for (int cz = 0; cz <= (dims.width() - 1) >> 4; cz++) {
                WorldgenForceGuard.forceChunk(level, cx, cz);
                LevelChunk chunk = level.getChunk(cx, cz);
                int xLo = Math.max(startX, cx << 4);
                int xHi = Math.min(endX, (cx << 4) + 15);
                int zLo = Math.max(0, cz << 4);
                int zHi = Math.min(dims.width() - 1, (cz << 4) + 15);
                for (int x = xLo; x <= xHi; x++) {
                    for (int z = zLo; z <= zHi; z++) {
                        for (int y = BuilderWorldLayout.TRAIN_Y; y <= topY; y++) {
                            SilentBlockOps.setBlockSectionLocal(level, chunk,
                                    pos.set(x, y, z).immutable(), air);
                        }
                    }
                }
                chunk.setUnsaved(true);
            }
        }
        for (int i = 0; i < previousCarriages; i++) {
            EditorPlotSnapshots.clear(BuilderDirtyCheck.snapshotKey(i));
        }
    }

    private static boolean isAlreadySetUp(ServerLevel level) {
        BlockPos probe = new BlockPos(0, BuilderWorldLayout.Y_GRASS, 0);
        WorldgenForceGuard.forceChunk(level, probe.getX() >> 4, probe.getZ() >> 4);
        return level.getBlockState(probe).is(Blocks.GRASS_BLOCK);
    }

    // ---- platform ----

    /**
     * Two layers over the whole 300×300 box: bedrock, then grass on top of it.
     *
     * <p>Written section-local (no light engine, no neighbour updates, no block entities) because
     * this is 180 000 blocks and every one of them is a plain cube under open sky — skylight above
     * the surface is unaffected by adding floor beneath it, so there is nothing for the light
     * engine to recompute.</p>
     */
    private static void stampPlatform(ServerLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minChunkX = BuilderWorldLayout.MIN_XZ >> 4;
        int maxChunkX = BuilderWorldLayout.MAX_XZ >> 4;
        int minChunkZ = BuilderWorldLayout.MIN_XZ >> 4;
        int maxChunkZ = BuilderWorldLayout.MAX_XZ >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                WorldgenForceGuard.forceChunk(level, cx, cz);
                LevelChunk chunk = level.getChunk(cx, cz);

                int xLo = Math.max(BuilderWorldLayout.MIN_XZ, cx << 4);
                int xHi = Math.min(BuilderWorldLayout.MAX_XZ, (cx << 4) + 15);
                int zLo = Math.max(BuilderWorldLayout.MIN_XZ, cz << 4);
                int zHi = Math.min(BuilderWorldLayout.MAX_XZ, (cz << 4) + 15);

                for (int x = xLo; x <= xHi; x++) {
                    for (int z = zLo; z <= zHi; z++) {
                        SilentBlockOps.setBlockSectionLocal(level, chunk,
                                pos.set(x, BuilderWorldLayout.Y_BEDROCK, z).immutable(), BEDROCK);
                        SilentBlockOps.setBlockSectionLocal(level, chunk,
                                pos.set(x, BuilderWorldLayout.Y_GRASS, z).immutable(), GRASS);
                    }
                }
                chunk.setUnsaved(true);
            }
        }
    }

    // ---- track ----

    /**
     * A straight run the full width of the platform, using the same authored track templates and
     * per-tile variant picks the real line uses — {@link TrackGenerator#ensureTracksForChunk} is
     * the runtime painter, so the builder's track looks exactly like the one in a real world.
     *
     * <p>It bails on chunks that aren't FULL yet, hence the force pass first.</p>
     */
    private static void stampTrack(ServerLevel level, CarriageDims dims) {
        TrackGeometry g = TrackGeometry.from(dims, BuilderWorldLayout.TRAIN_Y);
        Set<Long> filled = new HashSet<>();
        int minChunkX = BuilderWorldLayout.MIN_XZ >> 4;
        int maxChunkX = BuilderWorldLayout.MAX_XZ >> 4;
        int minChunkZ = g.trackZMin() >> 4;
        int maxChunkZ = g.trackZMax() >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                WorldgenForceGuard.forceChunk(level, cx, cz);
                TrackGenerator.ensureTracksForChunk(level, cx, cz, g, filled);
            }
        }
    }

    // ---- train ----

    /**
     * Park an empty train on the track, centred on the origin.
     *
     * <p>A run of more than one carriage is wrapped in half-flatbed pads the way
     * {@code TrainAssembler.spawnGroup} wraps a group, so the silhouette matches what the game
     * actually produces. Contents are never applied — the 4-arg {@code placeAt} stamps shell and
     * parts only, which is precisely "an empty carriage".</p>
     */
    private static void stampTrain(ServerLevel level, CarriageDims dims, int carriages) {
        Optional<CarriageVariant> variant = currentSource(level).or(BuilderWorldSetup::defaultVariant);
        if (variant.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Builder world: no carriage variants registered — skipping train");
            return;
        }
        stampTrain(level, dims, carriages, variant.get());
    }

    /**
     * The variant this world was last stamped from, when it still resolves.
     *
     * <p>What makes a mode switch keep your work: without it, changing from three carriages to one
     * would re-stamp the built-in default and silently throw away the template you chose in New.</p>
     */
    public static Optional<CarriageVariant> currentSource(ServerLevel level) {
        String recorded = DungeonTrainWorldData.get(level).builderVariant();
        return recorded == null || recorded.isEmpty()
                ? Optional.empty()
                : CarriageVariantRegistry.find(recorded);
    }

    /** Stamp {@code carriages} copies of {@code source} onto the track, and record what they came from. */
    private static void stampTrain(ServerLevel level, CarriageDims dims, int carriages,
                                   CarriageVariant source) {
        Optional<CarriageVariant> variant = Optional.of(source);

        int startX = BuilderWorldLayout.trainStartX(carriages, dims);
        int halfPad = CarriagePlacer.halfPadLen(dims);
        boolean pads = BuilderWorldLayout.usesPads(carriages);
        int enclosedX = pads ? startX + halfPad : startX;

        // Force every chunk the train touches before stamping — placeAt reads and writes block
        // states directly and would otherwise sync-load mid-stamp.
        int endX = startX + BuilderWorldLayout.totalTrainLength(carriages, dims);
        for (int cx = startX >> 4; cx <= endX >> 4; cx++) {
            for (int cz = 0; cz <= (dims.width() - 1) >> 4; cz++) {
                WorldgenForceGuard.forceChunk(level, cx, cz);
            }
        }

        if (pads) {
            CarriagePlacer.placeHalfFlatbedPad(level,
                    new BlockPos(startX, BuilderWorldLayout.TRAIN_Y, 0),
                    CarriagePlacer.HalfPadSide.BACK, dims);
            CarriagePlacer.placeHalfFlatbedPad(level,
                    new BlockPos(enclosedX + carriages * dims.length(), BuilderWorldLayout.TRAIN_Y, 0),
                    CarriagePlacer.HalfPadSide.FRONT, dims);
        }

        // Remember where the blocks came from, so a mode switch re-stamps this carriage rather than
        // the registry's first. This is the source, not the name the build saves as.
        DungeonTrainWorldData.get(level).setBuilderVariant(source.id());

        for (int i = 0; i < carriages; i++) {
            BlockPos origin = carriageOrigin(dims, carriages, i);
            CarriagePlacer.placeAt(level, origin, variant.get(), dims);
            // Baseline for the unsaved-changes check: the world as stamped, captured immediately
            // after, exactly as the editor does after each of its own stamps.
            EditorPlotSnapshots.capture(BuilderDirtyCheck.snapshotKey(i), level, origin,
                    dims.length(), dims.height(), dims.width());
        }
    }

    /**
     * Apply a <b>New</b> selection to this world: re-shape it for the chosen mode if that changed,
     * then stamp the carriage from the chosen source.
     *
     * <p>Nothing on disk is touched. An empty {@code name} leaves the build as a draft — it exists
     * to build in, and only becomes a template when the builder saves and names it. That's why New
     * doesn't demand a name: you rarely know what a thing is called before you've made it.</p>
     *
     * @return true if the world was stamped
     */
    public static boolean applyNew(ServerLevel level, BuilderNewRequest request) {
        if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return false;
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        BuilderMode mode = request.mode();

        BuilderMode previousMode = BuilderMode.fromId(data.builderMode()).orElse(mode);
        int previousCarriages = previousMode.carriageCount();
        clearTrain(level, dims, previousCarriages);
        data.setBuilderMode(mode.id());

        // A stage doesn't name a carriage — it decides which parts get stamped onto one. Selecting
        // it before the stamp is the whole mechanism: CarriagePlacer.placeAt reads
        // EditorStageSelection.effective() and routes to the per-stage parts overlay, which is
        // exactly what makes a desert carriage look like the desert. Without this the builder just
        // inherits whatever stage the editor last had focused.
        if (!request.stageId().isEmpty()) {
            EditorStageSelection.select(request.stageId());
        }

        int carriages = mode.carriageCount();
        if (carriages > 0) {
            stampTrain(level, dims, carriages, request.shell());
            overlaySelection(level, dims, carriages, request);
        } else {
            data.setBuilderVariant(request.shell().id());
        }
        data.setBuilderName(request.name());
        data.setBuilderStage(request.stageId());
        data.setBuilderSubType(request.subType().id(), request.partKindId());
        // A new build is a new thing to mirror — carrying the last build's axes over would apply
        // them to geometry that was never authored against them.
        data.setBuilderMirror(BuilderMirrorFlags.DEFAULT);
        LOGGER.info("[DungeonTrain] Builder new: mode '{}', {} '{}' on shell '{}', stage '{}', from '{}', name '{}'",
                mode.id(), request.subType().id(),
                request.picked().isEmpty() ? "<none>" : request.picked(), request.shell().id(),
                request.stageId().isEmpty() ? "<none>" : request.stageId(),
                request.wholeCarriageId().isEmpty() ? "<blank>" : request.wholeCarriageId(),
                request.name().isEmpty() ? "<draft>" : request.name());
        return true;
    }

    /**
     * Put the chosen thing on the parked shell.
     *
     * <p>A room and a part aren't things you can stand a train up out of on their own — a room needs
     * a carriage to be inside and a part needs one to sit on — so the shell is stamped first and the
     * selection goes over the top of it, exactly as the matching editor plot shows them.</p>
     *
     * <p>Re-baselines the dirty snapshots afterwards: {@code stampTrain} captured them against the
     * bare shell, and leaving them there would make a freshly-stamped build read as edited.</p>
     *
     * <p>A whole carriage is the exception to "over the top of the shell": when the builder picked
     * a saved build rather than a Stage, that build <em>replaces</em> the parked shell — see
     * {@link WholeCarriagePlacer#placeAt}, which clears the volume before stamping.</p>
     */
    private static void overlaySelection(ServerLevel level, CarriageDims dims, int carriages,
                                         BuilderNewRequest request) {
        Optional<WholeCarriage> savedBuild = WholeCarriageRegistry.find(request.wholeCarriageId());
        if (request.picked().isEmpty() && savedBuild.isEmpty()) {
            return;   // whole carriage started from a Stage: the bare shell is the build
        }
        for (int i = 0; i < carriages; i++) {
            BlockPos origin = carriageOrigin(dims, carriages, i);
            switch (request.subType()) {
                case CARRIAGE_ROOM -> CarriageContentsRegistry.find(request.picked()).ifPresent(
                        contents -> CarriageContentsPlacer.placeAt(level, origin, contents, dims));
                case PARTS -> {
                    if (request.partKind() != null) {
                        // relight = true: this is a plot you look at, not a train being assembled
                        // section-local during worldgen.
                        CarriagePartPlacer.placeAt(level, origin, request.partKind(),
                                request.picked(), dims, 0L, i, true);
                    }
                }
                case WHOLE_CARRIAGE -> savedBuild.ifPresent(
                        build -> WholeCarriagePlacer.placeAt(level, origin, build, dims));
            }
            EditorPlotSnapshots.capture(BuilderDirtyCheck.snapshotKey(i), level, origin,
                    dims.length(), dims.height(), dims.width());
        }
    }

    // ---- Open ----

    /**
     * Load an existing template into this world for editing.
     *
     * <p><b>The contract here is the inverse of {@link #applyNew}'s, deliberately.</b> New is
     * lenient by design: an id that doesn't resolve degrades to a bare shell, which is a perfectly
     * good thing to start a <em>new</em> build from. Open cannot borrow that leniency, because it
     * points {@code builderName} at a template that already exists — so "couldn't find it, here's an
     * empty carriage instead" would mean the next Save writes an empty carriage over the file the
     * builder was trying to edit.</p>
     *
     * <p>So: everything is resolved before a single block moves ({@link #resolveOpen}), and
     * {@code builderName} is set only once every stamp has reported success. A failure leaves the
     * world untouched and the build unnamed rather than half-loaded and armed to overwrite.</p>
     *
     * <p>It also <em>restores</em> the metadata {@code applyNew} blanks. A new build has no stage or
     * mirror axes to inherit; an opened one has both already on disk, and dropping them would make
     * open-then-save quietly strip the mirroring off a template that was authored with it.</p>
     *
     * @return true if the world now holds the requested template
     */
    public static boolean applyOpen(ServerLevel level, BuilderMode mode, BuilderOpenRequest request) {
        return applyOpen(level, mode, request, "");
    }

    /**
     * As {@link #applyOpen(ServerLevel, BuilderMode, BuilderOpenRequest)}, dressed for one stage.
     *
     * <p>{@code browsedStageId} is where the Open grid was standing when the tile was clicked — the
     * Carriage Room arm lists the stages and the carriage variants under each — and it decides the
     * <em>parts overlay</em> the carriage is stamped with, nothing else.</p>
     *
     * <p>Pointedly not the same thing as the stage recorded on the build. That one stays the
     * template's own link, because {@code BuilderSave.saveWholeCarriage} feeds it to
     * {@code linkStage}: writing the browsed stage there would mean looking at {@code windowed} under
     * {@code desert} and hitting Save re-gates a built-in carriage to desert-only spawning, which is
     * not something browsing should be able to do.</p>
     *
     * @param browsedStageId the stage being browsed, or empty to use the template's own link
     */
    public static boolean applyOpen(ServerLevel level, BuilderMode mode, BuilderOpenRequest request,
                                    String browsedStageId) {
        if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return false;
        }
        if (request == null || request.isEmpty()) {
            return false;
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();

        int carriages = mode.carriageCount();
        if (carriages <= 0) {
            // The track modes park no carriage, so there is no volume to load a template into.
            // BuilderOpenOptions.isOpenable already says so; this is the server-side backstop.
            LOGGER.warn("[DungeonTrain] Builder open: mode '{}' has no carriage to open into", mode.id());
            return false;
        }

        Optional<Resolved> resolved = resolveOpen(level, dims, request);
        if (resolved.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Builder open: could not resolve {} '{}' — world left alone",
                    request.kind().id(), request.id());
            return false;
        }
        Resolved open = resolved.get();

        BuilderMode previousMode = BuilderMode.fromId(data.builderMode()).orElse(mode);
        clearTrain(level, dims, previousMode.carriageCount());
        data.setBuilderMode(mode.id());

        // Before the stamp, for the same reason applyNew does it: CarriagePlacer.placeAt reads
        // EditorStageSelection.effective() to route the per-stage parts overlay, so a stage chosen
        // afterwards would have no effect on the blocks that were just laid down.
        String stageId = open.stageId();
        String shownStage = browsedStageId == null || browsedStageId.isEmpty()
                ? stageId
                : browsedStageId;
        if (!shownStage.isEmpty()) {
            EditorStageSelection.select(shownStage);
        }

        stampTrain(level, dims, carriages, open.shell());
        if (!overlayOpen(level, dims, carriages, request, open)) {
            LOGGER.error("[DungeonTrain] Builder open: stamping {} '{}' failed after resolving — "
                    + "leaving the build unnamed so a save cannot overwrite it",
                    request.kind().id(), request.id());
            return false;
        }

        // Only now: the world genuinely holds this template, so Save may point at it.
        data.setBuilderName(request.id());
        data.setBuilderStage(stageId);
        data.setBuilderSubType(request.subType().id(), request.partKindId());
        data.setBuilderMirror(open.mirror());

        for (int i = 0; i < carriages; i++) {
            BlockPos origin = carriageOrigin(dims, carriages, i);
            EditorPlotSnapshots.capture(BuilderDirtyCheck.snapshotKey(i), level, origin,
                    dims.length(), dims.height(), dims.width());
        }
        LOGGER.info("[DungeonTrain] Builder open: {} '{}' into mode '{}' on shell '{}', stage '{}'"
                        + " (shown as '{}')",
                request.kind().id(), request.id(), mode.id(), open.shell().id(),
                stageId.isEmpty() ? "<none>" : stageId,
                shownStage.isEmpty() ? "<none>" : shownStage);
        return true;
    }

    /**
     * Everything {@link #applyOpen} needs, gathered before the world is touched.
     *
     * @param shell        the carriage to park — the opened template itself for a carriage, and the
     *                     thing a room or part is shown on otherwise
     * @param wholeCarriage the saved build to stamp over the shell, when the opened carriage has one
     * @param stageId      the stage the template is linked to, empty when it isn't
     * @param mirror       the mirror axes the template was authored with
     */
    private record Resolved(CarriageVariant shell, Optional<WholeCarriage> wholeCarriage,
                            String stageId, BuilderMirrorFlags mirror) {}

    /**
     * Look the request up in full, or answer empty.
     *
     * <p>This is where Open earns its separate code path. Every lookup that {@code applyNew} would
     * have shrugged off is fatal here, <em>including the footprint gate</em>: a whole carriage saved
     * at different {@link CarriageDims} is registered but unreadable, and
     * {@link WholeCarriagePlacer#placeAt} answers that by returning false — a boolean
     * {@code applyNew}'s overlay discards. Checking the same gate here, up front, is what turns a
     * silent bare shell into a refusal.</p>
     */
    private static Optional<Resolved> resolveOpen(ServerLevel level, CarriageDims dims,
                                                  BuilderOpenRequest request) {
        return switch (request.kind()) {
            case CARRIAGE -> resolveCarriage(level, dims, request.id());
            case CONTENTS -> CarriageContentsRegistry.find(request.id()).isPresent()
                    ? shellOnly(level)
                    : Optional.empty();
            case PART -> request.partKind() != null
                    && CarriagePartRegistry.isKnown(request.partKind(), request.id())
                    ? shellOnly(level)
                    : Optional.empty();
        };
    }

    /**
     * Opening a carriage: the saved whole carriage when there is one, the bare shell otherwise.
     *
     * <p>A builder Save writes both — a {@code WholeCarriage} holding shell and interior together,
     * and a {@code CarriageVariant} shell so the thing still spawns in trains (see
     * {@code BuilderSave.saveWholeCarriage}). Preferring the whole carriage is therefore not a
     * guess: it is strictly the more complete of the two records of the same build. Built-in
     * carriages have only the shell, and open perfectly well as one.</p>
     */
    private static Optional<Resolved> resolveCarriage(ServerLevel level, CarriageDims dims, String id) {
        Optional<CarriageVariant> shell = CarriageVariantRegistry.find(id);
        Optional<WholeCarriage> saved = WholeCarriageRegistry.find(id)
                // Registered is not the same as readable at this world's dims — filter on the
                // template the placer would actually get, not on the registry entry.
                .filter(wc -> WholeCarriageTemplateStore.get(level, wc, dims).isPresent());
        if (shell.isEmpty() && saved.isEmpty()) {
            return Optional.empty();
        }
        // A whole carriage always has a shell of the same name (Save writes the pair), but fall
        // back to the parked one rather than failing if that invariant is ever broken on disk.
        CarriageVariant parked = shell.or(() -> currentSource(level)).or(BuilderWorldSetup::defaultVariant)
                .orElse(null);
        if (parked == null) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(parked, saved, stageOf(id), mirrorOf(parked, dims)));
    }

    /** A room or a part: the template is elsewhere, so only the carriage to show it on is resolved. */
    private static Optional<Resolved> shellOnly(ServerLevel level) {
        return currentSource(level).or(BuilderWorldSetup::defaultVariant)
                // Contents and parts carry no stage link or mirror sidecar of their own — those are
                // properties of a carriage — so they open with the same defaults New would give.
                .map(shell -> new Resolved(shell, Optional.empty(), "", BuilderMirrorFlags.DEFAULT));
    }

    /** The stage a carriage template is linked to, empty when it's Custom or unknown. */
    private static String stageOf(String variantId) {
        String stage = CarriageWeights.current().stageIdFor(variantId);
        return stage == null ? "" : stage;
    }

    /**
     * The mirror axes recorded on the template's sidecar — the read side of
     * {@code BuilderSave.carryMirrorToTemplate}.
     *
     * <p>Falls back to {@link BuilderMirrorFlags#DEFAULT} rather than failing: an unreadable sidecar
     * costs the builder a toggle, and refusing to open a template whose geometry is perfectly fine
     * would be a far worse trade.</p>
     */
    private static BuilderMirrorFlags mirrorOf(CarriageVariant variant, CarriageDims dims) {
        try {
            CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(variant, dims);
            return new BuilderMirrorFlags(sidecar.mirrorX(), sidecar.mirrorY(), sidecar.mirrorZ(),
                    sidecar.mirrorVariants());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Builder open: could not read mirror flags for {}: {}",
                    variant.id(), t.toString());
            return BuilderMirrorFlags.DEFAULT;
        }
    }

    /**
     * Put the opened template onto the parked shell, reporting whether every carriage took it.
     *
     * <p>The returned boolean is the whole difference from {@link #overlaySelection}, which drops
     * {@link WholeCarriagePlacer#placeAt}'s result on the floor. Here it propagates, because the
     * caller uses it to decide whether this build is allowed to have a name.</p>
     */
    private static boolean overlayOpen(ServerLevel level, CarriageDims dims, int carriages,
                                       BuilderOpenRequest request, Resolved open) {
        for (int i = 0; i < carriages; i++) {
            BlockPos origin = carriageOrigin(dims, carriages, i);
            switch (request.kind()) {
                case CARRIAGE -> {
                    if (open.wholeCarriage().isPresent()
                            && !WholeCarriagePlacer.placeAt(level, origin, open.wholeCarriage().get(), dims)) {
                        return false;
                    }
                    // No whole carriage: stampTrain already laid down the shell, which *is* the build.
                }
                case CONTENTS -> {
                    Optional<CarriageContents> contents = CarriageContentsRegistry.find(request.id());
                    if (contents.isEmpty()) {
                        return false;   // resolved a moment ago; a reload between the two is fatal here
                    }
                    CarriageContentsPlacer.placeAt(level, origin, contents.get(), dims);
                }
                case PART -> {
                    if (request.partKind() == null) {
                        return false;
                    }
                    // relight = true: a plot you stand and look at, not a train being assembled
                    // section-local during worldgen.
                    CarriagePartPlacer.placeAt(level, origin, request.partKind(), request.id(),
                            dims, 0L, i, true);
                }
            }
        }
        return true;
    }

    /**
     * World origin of the {@code index}-th parked carriage. Shared by the stamp and the overlay so
     * the two can't drift over the flatbed-pad offset.
     */
    public static BlockPos carriageOrigin(CarriageDims dims, int carriages, int index) {
        int startX = BuilderWorldLayout.trainStartX(carriages, dims);
        int enclosedX = BuilderWorldLayout.usesPads(carriages)
                ? startX + CarriagePlacer.halfPadLen(dims)
                : startX;
        return new BlockPos(enclosedX + index * dims.length(), BuilderWorldLayout.TRAIN_Y, 0);
    }

    /**
     * The carriage to park. First registered variant — the builtin STANDARD shell in a stock
     * install, and whatever the author put first once they've added their own.
     */
    private static Optional<CarriageVariant> defaultVariant() {
        List<CarriageVariant> all = CarriageVariantRegistry.allVariants();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }
}
