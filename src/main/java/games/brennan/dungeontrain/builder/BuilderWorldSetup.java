package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.editor.EditorStageSelection;
import games.brennan.dungeontrain.ship.sable.WorldgenForceGuard;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartPlacer;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
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
        LOGGER.info("[DungeonTrain] Builder new: mode '{}', {} '{}' on shell '{}', stage '{}', name '{}'",
                mode.id(), request.subType().id(),
                request.picked().isEmpty() ? "<none>" : request.picked(), request.shell().id(),
                request.stageId().isEmpty() ? "<none>" : request.stageId(),
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
     */
    private static void overlaySelection(ServerLevel level, CarriageDims dims, int carriages,
                                         BuilderNewRequest request) {
        if (request.picked().isEmpty()) {
            return;   // whole carriage: the shell is the build
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
                case WHOLE_CARRIAGE -> { }
            }
            EditorPlotSnapshots.capture(BuilderDirtyCheck.snapshotKey(i), level, origin,
                    dims.length(), dims.height(), dims.width());
        }
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
