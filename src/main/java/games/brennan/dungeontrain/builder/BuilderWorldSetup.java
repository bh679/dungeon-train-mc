package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.EditorPlotEntityClearer;
import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.editor.EditorStageSelection;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.ship.sable.WorldgenForceGuard;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.editor.PortalRoomEditor;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.portal.PortalRoomSizes;
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
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
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
 * <p>Idempotent, keyed on the world having a builder mode recorded, so reopening a builder world
 * from the world list is a no-op rather than a second train stamped on top of the first.</p>
 *
 * <p><b>Train Dimensions gets none of the scenery.</b> A portal room stands in the empty basement
 * under the world, not on a platform, and the mode parks no train for a track to carry — so the
 * platform and the track are both skipped and the room hangs in open void. That costs nothing to
 * generate: the builder preset is a {@code minecraft:flat} with an <em>empty</em> layer list, so
 * void is what the generator already produces and the platform was only ever a stamp on top of
 * it.</p>
 */
public final class BuilderWorldSetup {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();

    private BuilderWorldSetup() {}

    /**
     * How many carriages are parked on the track right now.
     *
     * <p>The recorded count, not a re-derivation of it. Every path that stamps or clears the train
     * writes what it actually laid down, so the build volumes, the dirty check, the save cut, the
     * cinematic framing and the spawn standoff all read the same number the stamp used — rather
     * than five copies of an inference that can disagree with the blocks.</p>
     *
     * <p>Deriving it from mode and sub type nearly works and then doesn't: those answer "what is
     * this build for" and "what does it save as", and the Open screen's carriage list is where the
     * second one comes apart from the count. Browsing rooms from outside the train opens a
     * <em>carriage</em> template — it must save as a whole carriage, which is what {@code
     * BuilderSave} reads the sub type for, while standing alone on the track.</p>
     *
     * <p>Falls back to the mode's own count for a world saved before the field existed, which is
     * exactly what those worlds were stamped with.</p>
     */
    public static int parkedCarriages(DungeonTrainWorldData data) {
        int recorded = data.builderCarriages();
        if (recorded >= 0) {
            return recorded;
        }
        return BuilderWorldLayout.parkedCarriages(
                BuilderMode.fromId(data.builderMode()).orElse(null), null);
    }

    /**
     * Lift this build's ghost blocks out of the world, and re-baseline the dirty check against
     * what is left.
     *
     * <p>The last thing every stamp path does, and the ordering is the point. It runs <b>after</b>
     * the stamp so the shell it captures carries this build's parts overlay rather than the base
     * variant's, and the baselines are re-captured <b>after</b> the erase — {@code stampTrain} and
     * {@code overlaySelection} took theirs against a carriage that still had walls, and leaving
     * them there would make a freshly opened build read as edited the moment it appeared.</p>
     *
     * <p>Unconditional, including where it lifts nothing: it is also what <em>clears</em> the
     * record, so switching from a carriage room to a part doesn't leave the room's shell ghosting
     * around a carriage that has its own again.</p>
     */
    public static void refreshGhostCells(ServerLevel level, CarriageDims dims) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        BuilderMode mode = BuilderMode.fromId(data.builderMode()).orElse(null);
        int carriages = parkedCarriages(data);
        // Put the line back before reading it. A carriage-to-carriage mode switch re-stamps the
        // train but not the scenery, so without this the second lift finds the air the first one
        // left and records an empty track — see BuilderGhostCells.restoreTrack.
        BuilderGhostCells.restoreTrack(level, dims, BuilderGhostCells.fromTag(
                data.builderGhostCells(), level.holderLookup(Registries.BLOCK)));
        BuilderGhostCells.Cells cells = BuilderGhostCells.lift(level, dims, mode,
                subTypeFor(mode, data), carriages, data.builderGhostMode());
        data.setBuilderGhostCells(cells.isEmpty() ? null : BuilderGhostCells.toTag(cells));

        for (int i = 0; i < carriages; i++) {
            BlockPos origin = carriageOrigin(dims, carriages, i);
            EditorPlotSnapshots.capture(BuilderDirtyCheck.snapshotKey(i), level, origin,
                    dims.length(), dims.height(), dims.width());
        }
    }

    /**
     * Change what the builder does with the scenery around the build, and make the world match.
     *
     * <p>Two of the three modes are the same world — {@code GHOST} and {@code NONE} both lift, and
     * differ only in whether the client draws what was lifted — so a switch between them moves no
     * blocks. {@code SOLID} is the one that does, in both directions: leaving it lifts, and entering
     * it puts back exactly what was taken.</p>
     *
     * <p>Restored before the mode is stored, so a failure part-way leaves the world and the setting
     * describing the same thing.</p>
     */
    public static void setGhostMode(ServerLevel level, BuilderGhostMode mode) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        BuilderGhostMode current = data.builderGhostMode();
        if (current == mode) {
            return;
        }
        if (current.lifts() && !mode.lifts()) {
            BuilderGhostCells.restore(level, dims, parkedCarriages(data),
                    BuilderGhostCells.fromTag(data.builderGhostCells(),
                            level.holderLookup(Registries.BLOCK)));
            data.setBuilderGhostCells(null);
        }
        data.setBuilderGhostMode(mode);
        // Lifts when the new mode wants it and does nothing when the blocks are already gone, so
        // this covers SOLID -> GHOST without a second arm for it.
        refreshGhostCells(level, dims);
        LOGGER.info("[DungeonTrain] Builder ghost mode: '{}' -> '{}'", current.id(), mode.id());
    }

    /**
     * What this world is authoring, for the ghost decision.
     *
     * <p>Falls back to the mode's default rather than to "none" when nothing has narrowed it yet.
     * A title-screen Inside Carriage click parks a carriage and heads for a carriage room — the
     * same reasoning {@link BuilderWorldLayout#parkedCarriages} applies to a null sub type — and
     * answering "no sub type" there would leave that world the one place the ghosts never
     * appear.</p>
     */
    private static BuilderNewOptions.SubType subTypeFor(BuilderMode mode, DungeonTrainWorldData data) {
        if (mode == null) {
            return null;
        }
        return BuilderNewOptions.SubType.fromId(data.builderSubType())
                .orElseGet(() -> BuilderNewOptions.defaultSubTypeFor(mode));
    }

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

        if (hasScenery(mode)) {
            stampPlatform(level);
            stampTrack(level, dims);
        }
        // No sub type yet — this is the title-screen click, before New or Open has narrowed it —
        // so the mode's own count stands.
        int carriages = BuilderWorldLayout.parkedCarriages(mode, null);
        if (carriages > 0) {
            stampTrain(level, dims, carriages);
        }
        DungeonTrainWorldData.get(level).setBuilderCarriages(carriages);

        // Persist the mode: it was a title-screen click and lives nowhere else, so without this
        // a reopened builder world can't tell the client where the build bounds are.
        DungeonTrainWorldData.get(level).setBuilderMode(mode.id());
        DungeonTrainWorldData.get(level).setBuilderMirror(BuilderMirrorFlags.DEFAULT);

        refreshGhostCells(level, dims);

        LOGGER.info("[DungeonTrain] Builder world stamped for mode '{}' ({} carriage(s)) in {} ms",
                mode.id(), carriages, (System.nanoTime() - t0) / 1_000_000);
        return true;
    }

    /**
     * Re-shape an existing builder world for a different mode: strip the parked train and stamp
     * the new one, adding or removing the scenery if the modes disagree about wanting it.
     *
     * <p>The platform and the track used to be left alone unconditionally — every mode shared them,
     * and rebuilding 180 000 blocks to change a carriage count would be absurd. They are no longer
     * shared: Train Dimensions builds in open void. So the scenery is now rebuilt only when crossing
     * that boundary, which is at most once per switch and never on a carriage-to-carriage one.</p>
     *
     * <p>Discards whatever was in the old carriages, so callers own the unsaved-changes
     * conversation (see {@code BuilderSwitchPacket}).</p>
     */
    public static void restamp(ServerLevel level, BuilderMode mode) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        BuilderMode previousMode = BuilderMode.fromId(data.builderMode()).orElse(null);
        int previous = parkedCarriages(data);

        clearTrain(level, dims, previous);
        // Whatever room was standing goes too: it belongs to the mode being left, and leaving it
        // would strand an unowned volume in the middle of the next mode's train.
        clearPreviousRoom(level, data, dims);
        // A track template left standing would be scenery nobody asked for in a carriage build —
        // and a tunnel is tall enough to swallow the train.
        clearTrackPlot(level, data, dims);

        // The scenery, only when the two modes disagree about wanting it. A null previous mode is a
        // world whose mode was never recorded, which by definition predates the void mode and so
        // has scenery already.
        boolean had = previousMode == null || hasScenery(previousMode);
        boolean wants = hasScenery(mode);
        if (wants && !had) {
            stampPlatform(level);
            stampTrack(level, dims);
        } else if (!wants && had) {
            clearScenery(level);
        }

        // A mode switch changes the mode, not what is being authored: a build that stood alone
        // stays alone, and one that was a full run stays a full run, scaled to the new mode.
        int carriages = previous == 1 && mode.carriageCount() > 0
                ? 1
                : BuilderWorldLayout.parkedCarriages(mode, null);
        if (carriages > 0) {
            stampTrain(level, dims, carriages);
        }
        data.setBuilderCarriages(carriages);
        data.setBuilderMode(mode.id());
        // The name and sub type belonged to the build being discarded. Leaving them would point Save
        // at a template the world no longer holds — the one thing applyOpen's ordering exists to
        // prevent.
        data.setBuilderName("");
        data.setBuilderSubType("", "");
        refreshGhostCells(level, dims);
        LOGGER.info("[DungeonTrain] Builder world re-stamped: {} carriage(s) -> '{}' ({} carriage(s)),"
                        + " scenery {}",
                previous, mode.id(), carriages,
                wants == had ? "unchanged" : (wants ? "restored" : "removed"));
    }

    /**
     * Take the whole scene back to bare platform, before an Open stamps what was asked for.
     *
     * <p>The clears this replaced each erased <em>the volume about to be stamped into</em> — the
     * parked train's footprint, the outgoing track plot, the outgoing room's box. That is the right
     * answer to "don't stamp on top of the last build" and the wrong answer to "Open gives me the
     * template I opened": everything an author left <em>outside</em> a build volume — a tower on the
     * platform, a block floating over the train, a mob that wandered in — survived, and the scene
     * after Open was the new template plus every leftover from before it. The client only washed
     * those red ({@code OutOfBoundsWashRenderer}); nothing removed them.</p>
     *
     * <p>So: air from {@link BuilderWorldLayout#Y_STAND} up, across the whole platform box, plus the
     * entities standing in it. Bedrock and grass stay — they are the two courses the protection
     * guard never lets anyone author, so nothing can have gone wrong down there, and rebuilding them
     * is 180 000 blocks to no effect.</p>
     *
     * <p>Empty sections are skipped. The dimension is 96 tall and a builder world normally has
     * blocks only in the bottom one, so the skip is the difference between this and 8.6 million
     * writes; without it the wipe would be the most expensive thing Open does by an order of
     * magnitude.</p>
     *
     * <p>Every baseline goes with the blocks — {@link EditorPlotSnapshots#clearAll()} rather than the
     * per-key clears the old helpers each did — because after a wipe there is no volume left whose
     * snapshot still describes anything. The caller recaptures for what it stamps. Taking the whole
     * map is safe because every caller is gated on {@link BuilderWorldLayout#BUILDER_DIMENSION_TYPE}
     * and the store is per-server: a builder world is a builder world, so there is no Train Editor
     * plot open somewhere else whose baseline this would take with it.</p>
     *
     * @param relayTrack put the shared corridor back afterwards. False for a track open, which wants
     *                   the corridor gone on purpose so the line can be drawn as ghosts — a ghost
     *                   that is also a real block can be broken, stood on, and saved into the wrong
     *                   template.
     */
    private static void wipeScene(ServerLevel level, DungeonTrainWorldData data, CarriageDims dims,
                                  boolean relayTrack) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minChunk = BuilderWorldLayout.MIN_XZ >> 4;
        int maxChunk = BuilderWorldLayout.MAX_XZ >> 4;
        int topY = level.getMaxBuildHeight() - 1;

        for (int cx = minChunk; cx <= maxChunk; cx++) {
            for (int cz = minChunk; cz <= maxChunk; cz++) {
                WorldgenForceGuard.forceChunk(level, cx, cz);
                LevelChunk chunk = level.getChunk(cx, cz);

                int xLo = Math.max(BuilderWorldLayout.MIN_XZ, cx << 4);
                int xHi = Math.min(BuilderWorldLayout.MAX_XZ, (cx << 4) + 15);
                int zLo = Math.max(BuilderWorldLayout.MIN_XZ, cz << 4);
                int zHi = Math.min(BuilderWorldLayout.MAX_XZ, (cz << 4) + 15);
                boolean touched = false;

                for (int y = BuilderWorldLayout.Y_STAND; y <= topY; y++) {
                    // One probe per section rather than per block: the section index only changes
                    // every 16 rows, and an all-air section has nothing to erase.
                    if ((y & 15) == 0 || y == BuilderWorldLayout.Y_STAND) {
                        int index = chunk.getSectionIndex(y);
                        if (index < 0 || index >= chunk.getSections().length
                                || chunk.getSection(index).hasOnlyAir()) {
                            y = ((y >> 4) << 4) + 15;   // skip to the last row of this section
                            continue;
                        }
                    }
                    for (int x = xLo; x <= xHi; x++) {
                        for (int z = zLo; z <= zHi; z++) {
                            SilentBlockOps.setBlockSectionLocal(level, chunk,
                                    pos.set(x, y, z).immutable(), air);
                        }
                    }
                    touched = true;
                }
                if (touched) {
                    chunk.setUnsaved(true);
                }
            }
        }

        EditorPlotEntityClearer.discardNonPlayersIn(level,
                new BlockPos(BuilderWorldLayout.MIN_XZ, BuilderWorldLayout.Y_STAND,
                        BuilderWorldLayout.MIN_XZ),
                new Vec3i(BuilderWorldLayout.SIZE, topY - BuilderWorldLayout.Y_STAND + 1,
                        BuilderWorldLayout.SIZE));

        EditorPlotSnapshots.clearAll();
        // The plot went with everything else, so the world is no longer holding a track build.
        data.setBuilderTrackKind("");

        if (relayTrack) {
            stampTrack(level, dims);
        }
    }

    /**
     * Strip the platform and the track back to air, for a mode that builds in void.
     *
     * <p>The exact inverse of {@link #stampPlatform} plus {@link #stampTrack}, and written the same
     * way — section-local over the same 300 × 300 box. Clears the track rows as well as the two
     * platform courses, because the track sits above the grass rather than in it.</p>
     */
    private static void clearScenery(ServerLevel level) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minChunk = BuilderWorldLayout.MIN_XZ >> 4;
        int maxChunk = BuilderWorldLayout.MAX_XZ >> 4;

        for (int cx = minChunk; cx <= maxChunk; cx++) {
            for (int cz = minChunk; cz <= maxChunk; cz++) {
                WorldgenForceGuard.forceChunk(level, cx, cz);
                LevelChunk chunk = level.getChunk(cx, cz);

                int xLo = Math.max(BuilderWorldLayout.MIN_XZ, cx << 4);
                int xHi = Math.min(BuilderWorldLayout.MAX_XZ, (cx << 4) + 15);
                int zLo = Math.max(BuilderWorldLayout.MIN_XZ, cz << 4);
                int zHi = Math.min(BuilderWorldLayout.MAX_XZ, (cz << 4) + 15);

                for (int x = xLo; x <= xHi; x++) {
                    for (int z = zLo; z <= zHi; z++) {
                        for (int y = BuilderWorldLayout.Y_BEDROCK;
                             y <= BuilderWorldLayout.Y_TRACK_RAIL; y++) {
                            SilentBlockOps.setBlockSectionLocal(level, chunk,
                                    pos.set(x, y, z).immutable(), air);
                        }
                    }
                }
                chunk.setUnsaved(true);
            }
        }
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

    /**
     * Whether the mode wants the platform and the track under it.
     *
     * <p>False for Train Dimensions alone. Everything else is a train being looked at from
     * somewhere, and needs a floor to stand on and a line for the train to sit on; a portal room is
     * the whole scene.</p>
     */
    public static boolean hasScenery(BuilderMode mode) {
        return mode != BuilderMode.TRAIN_DIMENSIONS;
    }

    /**
     * Has this world already been stamped?
     *
     * <p>Keyed on the recorded builder mode rather than on a grass block at the origin. The grass
     * probe was a fine marker while every mode laid grass; a Train Dimensions world has none, so it
     * would answer "not set up" forever and re-stamp the room's surroundings on every load.</p>
     *
     * <p>The probe stays as the fallback. A world stamped before the mode was persisted has grass
     * and no mode, and reporting it unstamped would park a second train on top of the first.</p>
     */
    private static boolean isAlreadySetUp(ServerLevel level) {
        String recorded = DungeonTrainWorldData.get(level).builderMode();
        if (recorded != null && !recorded.isEmpty()) {
            return true;
        }
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

        // Read before anything below overwrites it: clearTrain has to erase the footprint that is
        // actually out there, and after setBuilderSubType the world data describes the new build.
        int previousCarriages = parkedCarriages(data);
        clearTrain(level, dims, previousCarriages);
        clearTrackPlot(level, data, dims);
        data.setBuilderMode(mode.id());

        // A stage doesn't name a carriage — it decides which parts get stamped onto one. Selecting
        // it before the stamp is the whole mechanism: CarriagePlacer.placeAt reads
        // EditorStageSelection.effective() and routes to the per-stage parts overlay, which is
        // exactly what makes a desert carriage look like the desert.
        //
        // Unconditional, including for an empty id. EditorStageSelection is one global sticky
        // singleton shared with the Train Editor, so skipping the call doesn't mean "no stage" —
        // it means "whatever stage was last selected", and a new build with no stage would come out
        // wearing the parts of the last one that had one.
        EditorStageSelection.select(request.stageId());

        int carriages = BuilderWorldLayout.parkedCarriages(mode, request.subType());
        if (carriages > 0) {
            stampTrain(level, dims, carriages, request.shell());
            overlaySelection(level, dims, carriages, request);
        } else {
            data.setBuilderVariant(request.shell().id());
        }
        data.setBuilderCarriages(carriages);
        data.setBuilderName(request.name());
        data.setBuilderStage(request.stageId());
        data.setBuilderSubType(request.subType().id(), request.partKindId());
        // A new build is a new thing to mirror — carrying the last build's axes over would apply
        // them to geometry that was never authored against them.
        data.setBuilderMirror(BuilderMirrorFlags.DEFAULT);
        refreshGhostCells(level, dims);
        LOGGER.info("[DungeonTrain] Builder new: mode '{}' ({} carriage(s)), {} '{}' on shell '{}', stage '{}', from '{}', name '{}'",
                mode.id(), carriages, request.subType().id(),
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
        return applyOpen(level, mode, request, "", null);
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
     * <p>{@code viewSubType} is the arm of the Open screen the tile was clicked in, and it decides
     * <b>how much train to park</b> — nothing else. It is not the same answer as
     * {@link BuilderOpenRequest#subType()}, which comes from the store the template lives in, and
     * the carriage list is where the two part company: a carriage browsed under a Stage is a
     * carriage template (so it must save as one) opened as a single room-sized build (so one
     * carriage is stamped). Deriving the count from the request instead put three on the track.</p>
     *
     * @param browsedStageId the stage being browsed, or empty to use the template's own link
     * @param viewSubType    the sub type the grid was showing, or null to follow the request's own
     */
    public static boolean applyOpen(ServerLevel level, BuilderMode mode, BuilderOpenRequest request,
                                    String browsedStageId,
                                    BuilderNewOptions.SubType viewSubType) {
        if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return false;
        }
        if (request == null || request.isEmpty()) {
            return false;
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();

        // A track-side template goes on its own plot, not into a carriage — different volume,
        // different store, different save arm. Branch before anything counts carriages.
        if (request.isTrack()) {
            return applyOpenTrack(level, mode, data, dims, request);
        }
        // A portal room is not stamped on a carriage either — it *is* the volume — so it takes its
        // own path before the carriage arithmetic below, which would reject it for parking no train.
        if (request.isPortalRoom()) {
            return openPortalRoom(level, data, dims, mode, request);
        }

        int carriages = BuilderWorldLayout.parkedCarriages(mode,
                viewSubType == null ? request.subType() : viewSubType);
        if (carriages <= 0) {
            // A carriage-side template in a mode that parks no carriage: nothing to load it into.
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

        // The whole scene, not just the carriage footprint: an Open hands back the template you
        // opened, and anything left standing beside it came from a build you already left.
        wipeScene(level, data, dims, hasScenery(mode));
        data.setBuilderMode(mode.id());

        // Before the stamp, for the same reason applyNew does it: CarriagePlacer.placeAt reads
        // EditorStageSelection.effective() to route the per-stage parts overlay, so a stage chosen
        // afterwards would have no effect on the blocks that were just laid down.
        String stageId = open.stageId();
        String shownStage = browsedStageId == null || browsedStageId.isEmpty()
                ? stageId
                : browsedStageId;
        // Unconditional, empty id included. The selection is a global that outlives this call, so
        // guarding on non-empty made browsing leak: open a carriage under `desert`, then open
        // anything with no stage of its own, and the second one came out wearing desert's parts.
        EditorStageSelection.select(shownStage);

        stampTrain(level, dims, carriages, open.shell());
        if (!overlayOpen(level, dims, carriages, request, open)) {
            LOGGER.error("[DungeonTrain] Builder open: stamping {} '{}' failed after resolving — "
                    + "leaving the build unnamed so a save cannot overwrite it",
                    request.kind().id(), request.id());
            return false;
        }

        // Only now: the world genuinely holds this template, so Save may point at it.
        data.setBuilderCarriages(carriages);
        data.setBuilderName(request.id());
        data.setBuilderStage(stageId);
        data.setBuilderSubType(request.subTypeToken(), request.partKindId());
        data.setBuilderMirror(open.mirror());

        // Lifts this build's ghosts and takes the dirty baselines afterwards — which is why the
        // per-volume capture that used to stand here is gone rather than duplicated.
        refreshGhostCells(level, dims);
        // The carriage count is in here because it is the one thing about an open you cannot see
        // from the outcome without counting blocks — and it is decided by the browsing arm rather
        // than by anything else on this line, so a wrong count looks like a correct open.
        LOGGER.info("[DungeonTrain] Builder open: {} '{}' into mode '{}' as '{}' ({} carriage(s))"
                        + " on shell '{}', stage '{}' (shown as '{}')",
                request.kind().id(), request.id(), mode.id(),
                viewSubType == null ? request.subType().id() : viewSubType.id(), carriages,
                open.shell().id(),
                stageId.isEmpty() ? "<none>" : stageId,
                shownStage.isEmpty() ? "<none>" : shownStage);
        return true;
    }

    // ---- track-side templates ----

    /**
     * Open a track tile, pillar section, tunnel or stairs adjunct onto its plot.
     *
     * <p>The carriage arm's shape, with the carriage taken out of it: resolve first and refuse if
     * the template isn't there, then clear, stamp, snapshot, and only then let {@code builderName}
     * point at the file — so a failed open can never arm a Save that overwrites what it failed to
     * load.</p>
     *
     * <p>What it does <em>not</em> do is re-lay the corridor around the plot. The track outside the
     * plot is scenery that every mode shares and {@code stampTrack} already put there; a track tile
     * opened at the tile grid lines up with it by construction (see {@link BuilderTrackPlot}).</p>
     */
    private static boolean applyOpenTrack(ServerLevel level, BuilderMode mode,
                                          DungeonTrainWorldData data, CarriageDims dims,
                                          BuilderOpenRequest request) {
        TrackKind kind = request.trackKind();
        String name = request.id();
        if (kind == null || !TrackVariantRegistry.contains(kind, name)) {
            LOGGER.warn("[DungeonTrain] Builder open: no {} template named '{}'",
                    kind == null ? "<no kind>" : kind.id(), name);
            return false;
        }

        // Whatever was here before goes first, all of it — a parked train from a carriage mode, the
        // last track plot (which may be a different kind with a different footprint), and anything
        // an author left lying around the platform.
        //
        // The corridor is not put back, on purpose. From here the rest of the line stops being
        // blocks: everything outside the plot is drawn as ghosts (BuilderTrackGhostShape), and a
        // ghost that is also a real block is not a ghost — it can be broken, walked on, and saved
        // into the wrong template. The wipe is what makes the drawn version the only version.
        wipeScene(level, data, dims, /*relayTrack*/ false);
        data.setBuilderMode(mode.id());

        BlockPos origin = BuilderTrackPlot.origin(kind, dims);
        Vec3i size = BuilderTrackPlot.footprint(kind, dims);
        forceChunksFor(level, origin, size);

        if (!stampTrackTemplate(level, kind, name, origin, dims)) {
            LOGGER.error("[DungeonTrain] Builder open: stamping {} '{}' failed — leaving the build "
                    + "unnamed so a save cannot overwrite it", kind.id(), name);
            return false;
        }

        data.setBuilderName(name);
        data.setBuilderTrackKind(kind.id());
        data.setBuilderStage("");
        data.setBuilderMirror(trackMirrorOf(kind, name, size));
        EditorPlotSnapshots.capture(BuilderDirtyCheck.snapshotKey(kind, name), level, origin,
                size.getX(), size.getY(), size.getZ());
        LOGGER.info("[DungeonTrain] Builder open: track {} '{}' into mode '{}' at {}",
                kind.id(), name, mode.id(), origin);
        return true;
    }

    /**
     * Lay the named template down at {@code origin}.
     *
     * <p>Tunnels go through {@link TunnelPlacer}'s named placers rather than the store directly,
     * because those carry the procedural fallback for a variant whose NBT is missing — a tunnel
     * painted from code is still a tunnel, where a bare hole is nothing. Every other kind ships a
     * bundled {@code default.nbt}, so the store resolves and the fallback would be dead code.</p>
     */
    private static boolean stampTrackTemplate(ServerLevel level, TrackKind kind, String name,
                                              BlockPos origin, CarriageDims dims) {
        if (kind == TrackKind.TUNNEL_SECTION) {
            TunnelPlacer.placeSectionNamed(level, origin, name);
            return true;
        }
        if (kind == TrackKind.TUNNEL_PORTAL) {
            TunnelPlacer.placePortalNamed(level, origin, false, name);
            return true;
        }
        Optional<StructureTemplate> stored = TrackVariantStore.get(level, kind, name, dims);
        if (stored.isEmpty()) {
            return false;
        }
        stored.get().placeInWorld(level, origin, origin,
                new StructurePlaceSettings().setIgnoreEntities(true), level.getRandom(), 3);
        return true;
    }

    /**
     * The mirror axes this template was authored with, off its own sidecar.
     *
     * <p>Track templates keep their mirror flags per {@code (kind, name)} in the
     * {@code .variants.json} beside the NBT, where a carriage keeps them on the variant. Reading the
     * template's own answer is what stops opening a tunnel from inheriting the mirror you last used
     * on a pillar.</p>
     */
    private static BuilderMirrorFlags trackMirrorOf(TrackKind kind, String name, Vec3i size) {
        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(kind, name, size);
        return new BuilderMirrorFlags(sidecar.mirrorX(), sidecar.mirrorY(), sidecar.mirrorZ(),
                sidecar.mirrorVariants());
    }

    /**
     * Erase the previous track plot back to air, and drop its baseline with it.
     *
     * <p>Same reasoning as {@link #clearTrain}: a baseline for a plot that no longer holds what it
     * described would have the next dirty check comparing against a ghost. Skipped entirely when
     * the world wasn't holding a track build, which is the common case.</p>
     *
     * <p>A plot that sat <em>in</em> the corridor takes a stretch of the shared track with it when
     * it goes — a tunnel is ten blocks long and the tile that replaces it is four, so the erase
     * would leave the line with holes either side of the new plot. So the corridor is re-laid
     * afterwards, by the same {@link #stampTrack} call that put it there in the first place. The
     * caller stamps its own template after this returns, so it wins over the restored track where
     * the two overlap.</p>
     */
    private static void clearTrackPlot(ServerLevel level, DungeonTrainWorldData data,
                                       CarriageDims dims) {
        TrackKind previous = BuilderTrackBuild.kindOf(data);
        if (previous == null) {
            return;
        }
        BlockPos origin = BuilderTrackPlot.origin(previous, dims);
        Vec3i size = BuilderTrackPlot.footprint(previous, dims);
        forceChunksFor(level, origin, size);
        erase(level, origin, size);
        EditorPlotSnapshots.clear(BuilderDirtyCheck.snapshotKey(previous, data.builderName()));
        data.setBuilderTrackKind("");
        // Unconditional: opening any track template erases the whole corridor, so leaving one has
        // to put all of it back, whichever kind was on the plot.
        stampTrack(level, dims);
    }

    /** Force every chunk a plot touches, so the stamp below never sync-loads mid-write. */
    private static void forceChunksFor(ServerLevel level, BlockPos origin, Vec3i size) {
        int maxX = origin.getX() + size.getX() - 1;
        int maxZ = origin.getZ() + size.getZ() - 1;
        for (int cx = origin.getX() >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = origin.getZ() >> 4; cz <= maxZ >> 4; cz++) {
                WorldgenForceGuard.forceChunk(level, cx, cz);
            }
        }
    }

    /**
     * Clear a box back to air.
     *
     * <p>Plain {@code setBlock} rather than {@link SilentBlockOps}: a track plot is at most a few
     * thousand blocks and sits at ground level under open sky, where the carriage clear is 180 000
     * blocks — and unlike that one, this box holds rails and torches whose neighbours genuinely need
     * telling.</p>
     */
    private static void erase(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    level.setBlock(origin.offset(dx, dy, dz), air, 3);
                }
            }
        }
    }

    /**
     * Open a portal room: clear the track and stand the room on the platform.
     *
     * <p>Nothing like the carriage arms above, because a room is not <em>on</em> anything. There is
     * no shell to park, no stage overlay to route and no mirror flags to carry — a room's mirroring
     * lives in its own variant sidecar, which {@code PortalRoomEditor} rebuilds at save time.</p>
     *
     * <p>The size comes from the template by way of a load: {@code PortalRoomSizes} only knows the
     * built-in figure until something has read the file this session, so opening a room authored at
     * 21 blocks without loading first would stand up an 11-block built-in one. This is the same
     * first line, and the same reason, as {@code PortalRoomEditor.stampPlot}.</p>
     *
     * <p>Ordering matches the carriage path and matters for the same reason: the world is stamped
     * first and {@code builderName} is set only once it holds the room, so a failed stamp leaves the
     * build unnamed and Save cannot overwrite the file.</p>
     */
    private static boolean openPortalRoom(ServerLevel level, DungeonTrainWorldData data,
                                          CarriageDims dims, BuilderMode mode,
                                          BuilderOpenRequest request) {
        String name = request.id();
        // Start from what is on disk. A resize made with the size steppers and never saved is a
        // change to the world, not to the template — PortalRoomSizes keeps it pending until a save
        // spends it, and without this it would follow the room into its next open and read as a
        // save that never happened.
        PortalRoomSizes.clearPending(name);
        PortalRoomTemplateStore.get(level, name, dims);
        Vec3i size = PortalRoomSizes.sizeOf(name, dims);

        // Everything standing goes, whatever it was — a parked train from a carriage mode, the
        // outgoing room (rooms differ in size, so a small one stamped where a large one stood used
        // to leave the old shell around it), and any stray build on the platform. It also resets
        // builderTrackKind, which is what stops a track template opened earlier from being written
        // over this room by Save — BuilderSave tests that field before the portal-room token.
        wipeScene(level, data, dims, hasScenery(mode));
        data.setBuilderMode(mode.id());

        BlockPos origin = BuilderWorldLayout.portalRoomOrigin(size);
        try {
            PortalRoomEditor.stampRoomInto(level, origin, size, name, dims, /*outline*/ false);
        } catch (RuntimeException e) {
            LOGGER.error("[DungeonTrain] Builder open: stamping portal room '{}' failed — "
                    + "leaving the build unnamed so a save cannot overwrite it", name, e);
            return false;
        }

        data.setBuilderName(name);
        data.setBuilderStage("");
        data.setBuilderSubType(request.subTypeToken(), request.partKindId());
        // A room's mirroring is authored in its own sidecar, not in the builder's flags; carrying
        // the last build's axes over would apply them to geometry never authored against them.
        data.setBuilderMirror(BuilderMirrorFlags.DEFAULT);

        EditorPlotSnapshots.capture(BuilderDirtyCheck.snapshotKey(0), level, origin,
                size.getX(), size.getY(), size.getZ());
        LOGGER.info("[DungeonTrain] Builder open: portal room '{}' into mode '{}' at {} ({}x{}x{})",
                name, mode.id(), origin, size.getX(), size.getY(), size.getZ());
        return true;
    }

    /**
     * Erase whatever room is standing, before a different one is stamped over the same ground.
     *
     * <p>Rooms differ in size, so stamping a small one where a large one stood would leave the old
     * room's outer shell around it — the new room inside the corpse of the last. Reads the outgoing
     * room's own size rather than the incoming one's, which is the whole point.</p>
     */
    private static void clearPreviousRoom(ServerLevel level, DungeonTrainWorldData data,
                                          CarriageDims dims) {
        if (!BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(data.builderSubType())) {
            return;
        }
        String previous = data.builderName();
        if (previous == null || previous.isEmpty()) {
            return;
        }
        Vec3i size = PortalRoomSizes.sizeOf(previous, dims);
        BlockPos origin = BuilderWorldLayout.portalRoomOrigin(size);
        PortalClear.clearBoxRelit(level, BoundingBox.fromCorners(origin,
                origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1)),
                PortalCorridorMask.NONE);
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
            // Unreachable, both of them: applyOpen branches to applyOpenTrack and openPortalRoom
            // before it resolves anything, because neither needs a carriage to sit in. Empty rather
            // than a throw — a refusal is what every other unresolvable arm answers.
            case TRACK, PORTAL_ROOM -> Optional.empty();
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
        Optional<WholeCarriage> saved = WholeCarriageRegistry.find(id);
        // Registered is not the same as readable at this world's dims, and the difference has to be
        // fatal on its own rather than folded into the emptiness check below. BuilderSave writes a
        // WholeCarriage *and* a same-named shell for every build, so a shell always resolves for
        // anything a builder saved — meaning "whole carriage unreadable" would otherwise fall
        // straight through to the bare shell, open under the template's name, and arm the next Save
        // to write an empty carriage over the file being edited. Exactly what Open exists to stop.
        if (saved.isPresent() && WholeCarriageTemplateStore.get(level, saved.get(), dims).isEmpty()) {
            LOGGER.warn("[DungeonTrain] Builder open: whole carriage '{}' is registered but has no "
                    + "template at {}x{}x{} — refusing rather than opening its bare shell",
                    id, dims.length(), dims.height(), dims.width());
            return Optional.empty();
        }
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
