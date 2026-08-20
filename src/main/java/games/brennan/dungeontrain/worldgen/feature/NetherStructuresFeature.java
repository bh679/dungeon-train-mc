package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;

import java.util.List;

/**
 * Places the Nether band core's structure pieces — <b>after</b> the core terrain exists.
 *
 * <p>This feature exists because of one ordering fact: vanilla places structure pieces during the structure
 * steps, and {@link NetherTransitionFeature} replaces the core's columns at {@code top_layer_modification},
 * the very last step. A fortress therefore used to build itself against the raised overworld mountain and
 * then have that mountain turned into netherrack and open air underneath it. The visible symptom was
 * fortresses with no legs: vanilla grows their supports with {@code StructurePiece.fillColumnDown}, which
 * walks downward only while it sees air or liquid, so against solid stone the walk stopped on the first
 * block and no support was ever built.</p>
 *
 * <p>So {@code ChunkGeneratorDecorationMixin} holds the pieces back on core chunks and this feature — placed
 * after {@code nether_transition} and before {@code track_bed} — writes them itself, with the same
 * {@link StructureStart#placeInChunk} call {@code ChunkGenerator.applyBiomeDecoration} would have made. Run
 * against finished Nether terrain, {@code fillColumnDown} walks down through air and lava and stops on
 * netherrack, building exactly the supports it builds in the Nether.</p>
 *
 * <p>Running before {@code track_bed} is deliberate: the track carves the train's corridor through whatever
 * stands in it, so a fortress on the line is tunnelled through rather than blocking the rails.</p>
 */
public class NetherStructuresFeature extends Feature<NoneFeatureConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN PLACE_ERRORS = new LogFirstN(5);

    public NetherStructuresFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        long t0 = GenProfiler.t0();
        try {
            WorldGenLevel level = ctx.level();
            if (!(level instanceof WorldGenRegion region)) return false;

            ChunkPos chunkPos = new ChunkPos(ctx.origin());
            if (!DeferredStructurePlacement.isDeferred(level, chunkPos)) return false;

            StructureManager structureManager = level.getLevel().structureManager().forWorldGenRegion(region);
            List<StructureStart> starts = structureManager.startsForStructure(chunkPos, structure -> true);
            if (starts.isEmpty()) return false;

            BoundingBox chunkBox = new BoundingBox(
                    chunkPos.getMinBlockX(), level.getMinBuildHeight(), chunkPos.getMinBlockZ(),
                    chunkPos.getMaxBlockX(), level.getMaxBuildHeight(), chunkPos.getMaxBlockZ());

            for (StructureStart start : starts) {
                try {
                    start.placeInChunk(level, structureManager, ctx.chunkGenerator(), ctx.random(),
                            chunkBox, chunkPos);
                } catch (Throwable t) {
                    // One bad structure must not abort the chunk — the rest of the band still generates.
                    PLACE_ERRORS.error(LOGGER,
                            "[DungeonTrain] Deferred structure placement failed in " + chunkPos, t);
                }
            }
            return true;
        } catch (Throwable t) {
            PLACE_ERRORS.error(LOGGER, "[DungeonTrain] NetherStructuresFeature.place failed at "
                    + ctx.origin(), t);
            return false;
        } finally {
            GenProfiler.add(GenProfiler.Bucket.CORE_REPLACE, t0);
        }
    }
}
