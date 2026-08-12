package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import games.brennan.dungeontrain.worldgen.WorldFloor;
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
 * Forces a solid 1-block-thick bedrock layer at the world's terrain floor —
 * {@link WorldFloor#bedrockY} — for every newly-generated chunk in a Dungeon
 * Train overworld.
 *
 * <p>That floor is the noise settings' {@code min_y}, not the dimension
 * type's: DT's dimension types run 80 blocks deeper so the portal system has
 * an empty basement under the world to stamp its twin structures into. The
 * bedrock caps that basement off.</p>
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
        // The Train Builder world is a DT dimension type in the overworld slot, so it passes both
        // gates above — but it is meant to be void outside its one 300×300 platform. Floor it and
        // the "platform in the void" becomes an infinite bedrock plane instead.
        if (level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) return;

        ChunkAccess chunk = event.getChunk();
        int chunkMinX = chunk.getPos().getMinBlockX();

        // Chuncks band: a VOID chunk is pure void and a SLICE chunk is a floating slab (flat cut-off
        // bottom) — neither gets a bedrock floor at minY. A FULL (vertically complete) chuncks chunk
        // keeps its floor like normal overworld. Per-chunk + deterministic, so this stays independent of
        // event ordering vs the slice-erosion handler. Chunks outside the chuncks band read FULL.
        ChuncksBand.Kind chuncksKind = ChuncksBand.kindOf(level, chunk.getPos().x, chunk.getPos().z);
        if (chuncksKind == ChuncksBand.Kind.VOID || chuncksKind == ChuncksBand.Kind.SLICE) {
            return;
        }

        // The disintegration band's void has no floor — skip bedrock in columns whose band
        // phase is void/End (middleRamp > 0). Computed per column so a chunk straddling a
        // phase edge keeps its overworld bedrock and drops it under the void, independent of
        // event ordering vs the erosion handler. (The nether phase keeps its floor: its
        // middleRamp is 0, so bedrock is placed normally there.)
        long bandStartX = DisintegrationBand.startX(level);
        boolean maybeBand = chunkMinX + 15 >= bandStartX;

        // The upside-down band flips the world's bedrock caps to the roof (WorldUpsideDownEvents),
        // so it has no floor either — skip bedrock in its columns when that inversion is enabled.
        // Gated so a false upsideDownBedrockRoof keeps the ordinary floor even in-band. Per-column,
        // like the void skip, so the two ChunkEvent.Load handlers stay order-independent.
        boolean roofInvert = DungeonTrainCommonConfig.isUpsideDownBedrockRoof();
        long upsideStartX = roofInvert ? UpsideDownBand.startX(level) : UpsideDownBand.OFF;
        boolean maybeUpside = upsideStartX != UpsideDownBand.OFF && chunkMinX + 15 >= upsideStartX;

        // The floor of TERRAIN, not of the level: a DT overworld's dimension type runs below its
        // noise settings so the portal system has an empty basement to work in, and the bedrock
        // belongs at the top of that basement — under the world, not under the basement.
        int floorY = WorldFloor.bedrockY(level);
        int sectionIdx = chunk.getSectionIndex(floorY);
        LevelChunkSection section = chunk.getSection(sectionIdx);
        int sectionBaseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIdx));
        int localY = floorY - sectionBaseY;
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            boolean voidColumn = maybeBand
                    && DisintegrationBand.middleRampAt(level, worldX) > 0.0;
            boolean upsideColumn = maybeUpside
                    && UpsideDownBand.isInBand(level, worldX);
            // In the exit crossfade the underside stays open void until the overworld coalesces —
            // WorldUpsideDownEvents clears the floor there while !exitFloorPresent, so match it here so
            // the two handlers agree per column. Once the floor has returned, bedrock is stamped normally.
            boolean exitVoidColumn = maybeUpside
                    && UpsideDownBand.isInExitFade(level, worldX)
                    && !UpsideDownBand.exitFloorPresent(level, worldX);
            if (voidColumn || upsideColumn || exitVoidColumn) continue;
            for (int dz = 0; dz < 16; dz++) {
                section.setBlockState(dx, localY, dz, bedrock, false);
            }
        }
        chunk.setUnsaved(true);
    }
}
