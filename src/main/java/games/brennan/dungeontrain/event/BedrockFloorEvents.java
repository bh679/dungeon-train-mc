package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Optional;

/**
 * Forces a solid 1-block-thick bedrock layer at {@code min_y} for every
 * newly-generated chunk in a Dungeon Train overworld.
 *
 * <p>The DT overworld noise settings inherit vanilla's noise function (which
 * is calibrated for {@code min_y = -64}) but use higher floors — 32 for the
 * default preset, up to 96 in Y-variants. The mismatch leaves the
 * {@code minecraft:bedrock_floor} surface rule with no terrain to convert in
 * deep ocean trenches and aquifer columns, producing holes through to the
 * void. This handler closes those holes by writing bedrock directly into the
 * chunk's bottom section after generation completes.</p>
 *
 * <p>Writes go through {@link LevelChunkSection#setBlockState} rather than
 * {@code LevelChunk.setBlockState} on purpose. Sable mixes into
 * {@code LevelChunk.setBlockState} to update its physics neighbourhood, which
 * reads sibling chunks via {@code ServerChunkCache.getChunk(...)}. Calling
 * that from inside the chunk-load completion handler (during spawn-area prep,
 * where neighbours are still mid-generation) livelocks the server thread —
 * observed once at 0.201.6, fixed here. Section writes skip every level-side
 * hook (block updates, light, observers, physics) and just stamp the palette;
 * we mark the chunk unsaved so the change persists to disk.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BedrockFloorEvents {

    private BedrockFloorEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        Optional<ResourceKey<DimensionType>> dimTypeKey =
                level.dimensionTypeRegistration().unwrapKey();
        if (dimTypeKey.isEmpty()
                || !DungeonTrain.MOD_ID.equals(dimTypeKey.get().location().getNamespace())) {
            return;
        }

        ChunkAccess chunk = event.getChunk();
        int chunkMinX = chunk.getPos().getMinBlockX();

        // The disintegration band's void has no floor — skip bedrock in columns whose band
        // phase is void/End (middleRamp > 0). Computed per column so a chunk straddling a
        // phase edge keeps its overworld bedrock and drops it under the void, independent of
        // event ordering vs the erosion handler.
        long bandStartX = DisintegrationBand.startX(level);
        boolean maybeBand = chunkMinX + 15 >= bandStartX;
        int fade = 0, voidHold = 0, endHold = 0, owHold = 0;
        if (maybeBand) {
            fade = DungeonTrainCommonConfig.getDisintegrationFadeBlocks();
            voidHold = DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks();
            endHold = DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks();
            owHold = DungeonTrainCommonConfig.getDisintegrationOverworldHoldBlocks();
        }

        int minY = level.getMinBuildHeight();
        int sectionIdx = chunk.getSectionIndex(minY);
        LevelChunkSection section = chunk.getSection(sectionIdx);
        int sectionBaseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIdx));
        int localY = minY - sectionBaseY;
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        for (int dx = 0; dx < 16; dx++) {
            boolean voidColumn = maybeBand
                    && Disintegration.middleRamp(chunkMinX + dx, bandStartX, fade, voidHold, endHold, owHold) > 0.0;
            if (voidColumn) continue;
            for (int dz = 0; dz < 16; dz++) {
                section.setBlockState(dx, localY, dz, bedrock, false);
            }
        }
        chunk.setUnsaved(true);
    }
}
