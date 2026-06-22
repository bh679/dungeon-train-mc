package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.feature.NetherTransitionFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Cleans overworld foliage (trees/leaves/flowers spilling in from neighbouring chunks)
 * off the stamped Nether-transition terrain. The {@code NetherTransitionFeature} buries
 * foliage it can see at generation, but a neighbour chunk decorated <i>after</i> this
 * chunk's feature ran can drop a tree across the seam — this pass, on
 * {@link ChunkEvent.Load} gated on {@link ChunkEvent.Load#isNewChunk()} (so it runs once
 * at generation, after all decoration, never on reload), strips those stragglers.
 *
 * <p>Only touches columns inside a nether band ({@link NetherBand#heightRampAt} {@code >
 * 0}) that the End band does not own ({@link DisintegrationBand#middleRampAt} {@code ==
 * 0}). Writes go through raw {@link LevelChunkSection#setBlockState}, the Sable-safe path
 * (mirroring {@code WorldDisintegrationEvents}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NetherTransitionEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private NetherTransitionEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        long startX = NetherBand.startX(level);
        if (startX == NetherBand.OFF) return;

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        if (chunkMinX + 15 < startX) return; // before the first band

        boolean[] band = new boolean[16];
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            // In a nether band, and not a column the End band owns (End wins).
            band[dx] = NetherBand.heightRampAt(level, worldX) > 0.0
                    && DisintegrationBand.middleRampAt(level, worldX) <= 0.0;
            if (band[dx]) any = true;
        }
        if (!any) return;

        boolean changed = false;
        for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
            LevelChunkSection section = chunk.getSection(sIdx);
            if (section.hasOnlyAir()) continue;
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
            for (int dx = 0; dx < 16; dx++) {
                if (!band[dx]) continue;
                for (int dz = 0; dz < 16; dz++) {
                    for (int ly = 0; ly < 16; ly++) {
                        BlockState cur = section.getBlockState(dx, ly, dz);
                        if (cur.isAir() || !NetherTransitionFeature.isStrippableFoliage(cur)) continue;
                        if (cur.hasBlockEntity()) {
                            chunk.removeBlockEntity(new BlockPos(chunkMinX + dx, baseY + ly, pos.getMinBlockZ() + dz));
                        }
                        section.setBlockState(dx, ly, dz, AIR, false);
                        changed = true;
                    }
                }
            }
        }
        if (changed) chunk.setUnsaved(true);
    }
}
