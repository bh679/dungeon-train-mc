package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.editor.EditorMirror;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.FallingBlockAnchor;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Arrays;

/**
 * Realises the <b>upside-down band</b> ({@link UpsideDownBand}) by mirroring each in-band column of
 * finished overworld terrain vertically around the carriage height — the ground you'd walk on becomes
 * a ceiling overhead, hills that rose above the train hang below it. Unlike the nether/End bands
 * (which reshape terrain during generation via density/biome hooks), this is a pure post-process
 * block mirror: vanilla generates normal overworld terrain in the band and this handler reflects it.
 *
 * <p>Runs on {@link ChunkEvent.Load} gated on {@link ChunkEvent.Load#isNewChunk()} (once at
 * generation, after all decoration — so neighbour tree spillover is already present — never on
 * reload, so player builds survive). Overworld-only (excludes Sable sub-levels), band-gated. Writes
 * go through the raw {@link LevelChunkSection#setBlockState} primitive — the Sable-safe path, never
 * {@code LevelChunk.setBlockState} (which would hit Sable's neighbourhood mixin mid-load and can
 * livelock the server thread; see {@code BedrockFloorEvents}). No light/neighbour/heightmap updates —
 * the band's client atmosphere handles lighting.</p>
 *
 * <p><b>Reflection.</b> The mirror plane is {@code M = trainY + mirrorPlaneOffset}. A source block at
 * {@code sy} maps to {@code 2·M − sy + ceilingGap} on the ground side (ceiling) or
 * {@code 2·M − sy − floorGap} on the hills side (hang); the {@code (M − floorGap, M + ceilingGap)}
 * gap around the plane is left as open air for the train to ride through. Because reflection can't be
 * done in place (source and target share the column), each column is snapshotted into an immutable
 * buffer first, then every target cell <em>pulls</em> {@link EditorMirror#verticalFlip} of its
 * mirrored source — correct even where the source and reflected spans overlap near the plane.</p>
 *
 * <p><b>Preserved / dropped.</b> The track corridor is left byte-for-byte intact by skipping its own
 * Z-columns ({@code [wallMinZ, wallMaxZ]}) — vertical reflection keeps X/Z, so only those columns
 * could damage the tube. The bedrock row at {@code minY} is never read or written (so the handler is
 * independent of {@code BedrockFloorEvents}' ordering). Free-standing fluids are dropped to air (no
 * ceiling waterfalls; waterlogged solids reflect normally), and block entities are dropped (a BE
 * can't be safely moved at this stage) — both rare in fresh overworld terrain.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldUpsideDownEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private WorldUpsideDownEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        long startX = UpsideDownBand.startX(level);
        if (startX == UpsideDownBand.OFF) return;

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        int chunkMinZ = pos.getMinBlockZ();
        if (chunkMinX + 15 < startX) return; // before the first band

        boolean[] inBand = new boolean[16];
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
            // Binary membership: the mirror is all-or-nothing per column. The three special bands are
            // disjoint by construction, so no End/Nether guard is needed here (unlike the nether foliage strip).
            inBand[dx] = UpsideDownBand.isInBand(level, chunkMinX + dx);
            if (inBand[dx]) any = true;
        }
        if (!any) return;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        int trainY = data.getTrainY();
        int mirror = trainY + DungeonTrainCommonConfig.getUpsideDownMirrorPlaneOffset();
        int ceilingGap = Math.max(0, DungeonTrainCommonConfig.getUpsideDownCeilingGap());
        int floorGap = Math.max(0, DungeonTrainCommonConfig.getUpsideDownFloorGap());

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();          // exclusive
        int floorGuard = minY;                          // never read or write the bedrock row

        // Preserve the track corridor: vertical reflection keeps X/Z, so only the corridor's own
        // Z-columns can damage the generated tube. Skipping them needs no re-stamping.
        TunnelGeometry tg = TunnelGeometry.from(TrackGeometry.from(data.dims(), trainY));
        int preserveZMin = tg.wallMinZ();
        int preserveZMax = tg.wallMaxZ();

        BlockState[] col = new BlockState[maxY - minY];
        boolean changed = false;

        for (int dz = 0; dz < 16; dz++) {
            int worldZ = chunkMinZ + dz;
            if (worldZ >= preserveZMin && worldZ <= preserveZMax) continue; // corridor tube preserved
            for (int dx = 0; dx < 16; dx++) {
                if (!inBand[dx]) continue;

                // 1) Snapshot the column into an immutable buffer (skip all-air sections).
                Arrays.fill(col, AIR);
                int srcMin = Integer.MAX_VALUE;
                int srcMax = Integer.MIN_VALUE;
                for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
                    LevelChunkSection section = chunk.getSection(sIdx);
                    if (section.hasOnlyAir()) continue;
                    int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (y <= floorGuard || y >= maxY) continue;
                        BlockState s = section.getBlockState(dx, ly, dz);
                        col[y - minY] = s;
                        if (!s.isAir()) {
                            if (y < srcMin) srcMin = y;
                            if (y > srcMax) srcMax = y;
                        }
                    }
                }
                if (srcMax < srcMin) continue; // empty column — nothing to mirror

                // 2) Write span = original terrain span ∪ reflected span (clipped, bedrock row excluded).
                int lo = Math.max(floorGuard + 1, Math.min(srcMin, 2 * mirror - floorGap - srcMax));
                int hi = Math.min(maxY - 1, Math.max(srcMax, 2 * mirror + ceilingGap - srcMin));

                // 3) Gather-write: each target y pulls its mirrored source from the pristine snapshot.
                for (int y = lo; y <= hi; y++) {
                    int sy;
                    if (y >= mirror + ceilingGap) {
                        sy = 2 * mirror + ceilingGap - y;   // ceiling — reflected from the ground side
                    } else if (y <= mirror - floorGap) {
                        sy = 2 * mirror - floorGap - y;     // hang — reflected from the hills side
                    } else {
                        sy = Integer.MIN_VALUE;             // open-air gap around the mirror plane
                    }

                    BlockState ns = AIR;
                    if (sy > floorGuard && sy < maxY) {
                        BlockState s = col[sy - minY];
                        if (!s.isAir() && !s.hasBlockEntity() && !(s.getBlock() instanceof LiquidBlock)) {
                            // Gravity-affected (Fallable) blocks become their stable equivalent so
                            // nothing falls out of the mirrored ceiling — the same anchoring the mod
                            // applies over corridors. Everything else is mirrored in place.
                            BlockState stable = FallingBlockAnchor.stableEquivalent(s);
                            ns = stable != null ? stable : EditorMirror.verticalFlip(s);
                        }
                    }

                    int sIdx = chunk.getSectionIndex(y);
                    LevelChunkSection sec = chunk.getSection(sIdx);
                    int ly = y - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
                    BlockState cur = sec.getBlockState(dx, ly, dz);
                    if (cur == ns) continue;                 // no-op (block states are interned singletons)
                    if (cur.hasBlockEntity()) {
                        chunk.removeBlockEntity(new BlockPos(chunkMinX + dx, y, worldZ));
                    }
                    sec.setBlockState(dx, ly, dz, ns, false);
                    changed = true;
                }
            }
        }
        if (changed) chunk.setUnsaved(true);
    }
}
