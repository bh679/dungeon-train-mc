package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.sable.WorldgenForceGuard;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
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

        LOGGER.info("[DungeonTrain] Builder world stamped for mode '{}' ({} carriage(s)) in {} ms",
                mode.id(), carriages, (System.nanoTime() - t0) / 1_000_000);
        return true;
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
        Optional<CarriageVariant> variant = defaultVariant();
        if (variant.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Builder world: no carriage variants registered — skipping train");
            return;
        }

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

        for (int i = 0; i < carriages; i++) {
            BlockPos origin = new BlockPos(enclosedX + i * dims.length(), BuilderWorldLayout.TRAIN_Y, 0);
            CarriagePlacer.placeAt(level, origin, variant.get(), dims);
        }
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
