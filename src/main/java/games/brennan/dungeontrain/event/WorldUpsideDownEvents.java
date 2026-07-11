package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.FallingBlockAnchor;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
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
 * buffer first, then every target cell <em>pulls</em> its mirrored source block — correct even where
 * the source and reflected spans overlap near the plane. Block <em>states</em> are written unchanged
 * (only their positions are mirrored); the upside-down <em>visual</em> flip of each block model is
 * applied at render time by {@code BlockRenderDispatcherUpsideDownMixin} (baked into the section mesh),
 * so the state must not also be flipped here or slabs/stairs would double-flip.</p>
 *
 * <p><b>Corridor.</b> The train corridor is flipped with the rest of the column (no longer preserved):
 * {@code TrackBedFeature} skips in-band chunks, so there are no during-gen rails to reflect, and after the
 * mirror this handler calls {@link TrackGenerator#layFlippedCorridor} to carve the carriage tube and stamp
 * the authored bed/rails onto the mirrored terrain — the train rides through the upside-down world itself.
 *
 * <p><b>Preserved / dropped.</b> The reflection itself never reads {@code minY}; that row is only written under
 * the bedrock-cap inversion ({@code upsideDownBedrockRoof}, default on) — which clears the surface-rule
 * floor at {@code minY} so the flipped world hangs over open void and stamps a continuous bedrock lid at
 * {@code roofY = 2·M + ceilingGap − minY} (where the old floor mirrors to, flush above the reflected
 * ceiling) across every in-band column, the corridor included. That inversion also makes
 * {@code BedrockFloorEvents} skip the band, so the floor is gone regardless of handler ordering; with it
 * off, this handler leaves {@code minY} untouched and the ordinary floor stays. Free-standing <b>water</b> reflects in as a
 * static source block (its flow frozen in-band by {@code FlowingFluidUpsideDownMixin}, so the mirrored
 * ocean/lake hangs from the ceiling without pouring off it); other free-standing fluids (lava) drop to
 * air, and waterlogged solids reflect normally. Block entities are dropped (a BE can't be safely moved
 * at this stage) — rare in fresh overworld terrain.</p>
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

        // Bedrock-cap inversion (upsideDownBedrockRoof): drop the surface-rule bedrock at minY so the
        // flipped world hangs over open void, and cap the reflected ceiling with a bedrock lid at roofY —
        // where the old minY floor mirrors to (one above the highest reflected-ceiling block, from source
        // minY+1), so the lid sits flush on the ceiling. Fixed per world; clamped into the build range.
        boolean roofInvert = DungeonTrainCommonConfig.isUpsideDownBedrockRoof();
        int roofY = UpsideDownBand.bedrockRoofY(mirror, ceilingGap, minY, maxY);
        int floorSectionIdx = chunk.getSectionIndex(minY);
        int floorLocalY = minY - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(floorSectionIdx));

        // Corridor geometry for the in-band track re-lay below. The corridor is NO LONGER preserved from
        // the mirror — it is flipped like the scenery, then TrackGenerator.layFlippedCorridor carves the
        // tube + stamps the rails onto the mirrored terrain (TrackBedFeature skips in-band chunks, so there
        // are no during-gen rails here to flip). This is what makes the train ride through the upside-down
        // world instead of a preserved plain tube.
        TrackGeometry g = TrackGeometry.from(data.dims(), trainY);

        BlockState[] col = new BlockState[maxY - minY];
        boolean changed = false;

        for (int dz = 0; dz < 16; dz++) {
            int worldZ = chunkMinZ + dz;
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
                        if (!s.isAir() && !s.hasBlockEntity()) {
                            if (s.getBlock() instanceof LiquidBlock) {
                                // Water reflects as a static SOURCE block (its flow is frozen in-band by
                                // FlowingFluidUpsideDownMixin) so the mirrored ocean/lake hangs from the
                                // ceiling instead of pouring off it; other free-standing fluids (lava)
                                // still drop to air.
                                if (s.getFluidState().is(FluidTags.WATER)) {
                                    ns = Blocks.WATER.defaultBlockState();
                                }
                            } else {
                                // Gravity-affected (Fallable) blocks become their stable equivalent so
                                // nothing falls out of the mirrored ceiling — the same anchoring the mod
                                // applies over corridors. Everything else keeps its source state: the
                                // upside-down VISUAL flip is baked in at render time by
                                // ModelBlockRendererUpsideDownMixin, so flipping the state here too
                                // would double-flip slabs/stairs.
                                BlockState stable = FallingBlockAnchor.stableEquivalent(s);
                                ns = stable != null ? stable : s;
                            }
                        }
                    }

                    int sIdx = chunk.getSectionIndex(y);
                    LevelChunkSection sec = chunk.getSection(sIdx);
                    int ly = y - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
                    BlockState cur = sec.getBlockState(dx, ly, dz);
                    if (cur == ns) continue;                 // no-op (block states are interned singletons)
                    if (cur.hasBlockEntity()) {
                        // Evict live AND pending (NBT-form) BEs — a bare removeBlockEntity leaves a
                        // still-pending structure BE (suspicious sand/gravel, chest) orphaned under the air.
                        SilentBlockOps.evictBlockEntity(chunk, new BlockPos(chunkMinX + dx, y, worldZ));
                    }
                    sec.setBlockState(dx, ly, dz, ns, false);
                    changed = true;
                }

                // Open the underside in-band: remove the surface-rule bedrock left at minY (the mirror
                // never touches that row), so the flipped column hangs over void instead of a floor.
                if (roofInvert) {
                    LevelChunkSection fSec = chunk.getSection(floorSectionIdx);
                    if (!fSec.getBlockState(dx, floorLocalY, dz).isAir()) {
                        fSec.setBlockState(dx, floorLocalY, dz, AIR, false);
                        changed = true;
                    }
                }
            }
        }

        // Bedrock roof: a continuous lid over every in-band column (corridor included, so the tube is
        // roofed too), stamped at roofY directly above the reflected ceiling — independent of the corridor
        // Z-skip above. Only when it lands above the train and inside the build range.
        if (roofInvert && roofY > mirror && roofY < maxY) {
            BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
            int roofSectionIdx = chunk.getSectionIndex(roofY);
            LevelChunkSection roofSec = chunk.getSection(roofSectionIdx);
            int roofLocalY = roofY - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(roofSectionIdx));
            for (int dz = 0; dz < 16; dz++) {
                int worldZ = chunkMinZ + dz;
                for (int dx = 0; dx < 16; dx++) {
                    if (!inBand[dx]) continue;
                    BlockState cur = roofSec.getBlockState(dx, roofLocalY, dz);
                    if (cur == bedrock) continue;
                    if (cur.hasBlockEntity()) {
                        SilentBlockOps.evictBlockEntity(chunk, new BlockPos(chunkMinX + dx, roofY, worldZ));
                    }
                    roofSec.setBlockState(dx, roofLocalY, dz, bedrock, false);
                    changed = true;
                }
            }
        }

        // Lay the corridor into the now-mirrored terrain — the in-band counterpart to TrackBedFeature
        // (which skips in-band chunks): carve the carriage tube through the flipped column and stamp the
        // authored bed/rails, so the train runs through the upside-down world instead of a preserved tube.
        // Runs after the mirror/roof passes (same handler → strictly ordered, no cross-handler priority).
        if (chunk instanceof LevelChunk levelChunk) {
            TrackGenerator.layFlippedCorridor(level, levelChunk, data.dims(), pos.x, pos.z, g);
            changed = true;
        }
        if (changed) chunk.setUnsaved(true);
    }
}
