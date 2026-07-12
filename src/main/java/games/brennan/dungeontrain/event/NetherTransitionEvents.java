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
 * Keeps overworld foliage (trees/leaves/flowers) out of the <b>netherrack crossfade + Nether
 * core</b> of the transition band, where green vegetation on netherrack would look wrong. The
 * mountain STAGES are now real, vegetated terrain (the band's height lives in the density router,
 * with highland biomes forced on top) — so foliage is KEPT there; only the netherrack zone
 * ({@link NetherBand#netherRampAt} {@code > 0}) is stripped.
 *
 * <p>Runs on {@link ChunkEvent.Load} gated on {@link ChunkEvent.Load#isNewChunk()} (once at
 * generation, after all decoration, never on reload). Only touches columns the End band does not
 * own ({@link DisintegrationBand#middleRampAt} {@code == 0}). Writes go through raw
 * {@link LevelChunkSection#setBlockState}, the Sable-safe path (mirroring {@code WorldDisintegrationEvents}).</p>
 *
 * <p><b>History:</b> this previously stripped foliage across the WHOLE band ({@code heightRampAt > 0})
 * to keep the old bare stamped mountains clean — which also deleted the trees/flowers the new
 * real-terrain mountains are meant to have. Scoped to the netherrack zone so mountains stay forested.</p>
 */
public final class NetherTransitionEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private NetherTransitionEvents() {}

        public static void onChunkLoad(net.minecraft.world.level.LevelAccessor chunkLevel, net.minecraft.world.level.chunk.ChunkAccess loadedChunk, boolean newChunk) {
        if (!newChunk) return;
        if (!(chunkLevel instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        long startX = NetherBand.startX(level);
        if (startX == NetherBand.OFF) return;

        ChunkAccess chunk = loadedChunk;
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        if (chunkMinX + 15 < startX) return; // before the first band

        boolean[] band = new boolean[16];
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            // Only the netherrack crossfade + Nether core (netherRamp > 0) — NOT the vegetated
            // mountain stages — and never a column the End band owns (End wins).
            band[dx] = NetherBand.netherRampAt(level, worldX) > 0.0
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
