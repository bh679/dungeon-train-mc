package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.FallingBlockAnchor;
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
 *
 * <p><b>Entry lead-in.</b> Immediately before the band ({@link UpsideDownBand#isInEntryLead}, inside
 * the End band's trailing void hold), {@code WorldDisintegrationEvents} stops eroding terrain so real
 * overworld survives as mirror source material, and this handler runs the same reflection there but
 * clips it to a growing <b>Y-window</b> centred on the train gap ({@link UpsideDownBand#revealYExtent},
 * driven by {@link UpsideDownBand#entryRevealRamp} 0→1 from the lead-in's start to the band boundary)
 * and <b>noise-dithers</b> the reveal across that window (probability 1 at track level falling to 0 at
 * the growing edge, via {@code Disintegration.coherentNoise} — the void-erosion primitive): at reveal 0
 * only the rows flanking the gap are shown, and the slab thickens upward (ceiling) and downward (hang)
 * as the reveal climbs, dissolving out of the void from track level so the upside-down world
 * materialises gradually instead of snapping on at one exact X. Floor removal runs across the whole lead-in (open void from the
 * start); the bedrock roof is stamped per column only once its window reaches roofY ("no bedrock until
 * the Y level reaches it"). The lead-in is part of the render-flipped zone
 * ({@link games.brennan.dungeontrain.client.ClientUpsideDownBand#isInBand}) and gets the flipped
 * corridor ({@code TrackBedFeature} skips it during gen), so its revealed terrain reads as inverted and
 * the track is continuous into the band.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldUpsideDownEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Decorrelates the overworld-reveal island mask from the mirror-disperse mask in the exit crossfade. */
    private static final long OW_ISLAND_SALT = 0x9E3779B97F4A7C15L;

    /** Enlarges the exit-crossfade island noise (bigger islands, proportionally bigger gaps). */
    private static final int EXIT_ISLAND_NOISE_SCALE = 4;

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
        boolean[] inLead = new boolean[16];
        boolean[] inExit = new boolean[16];
        double[] leadReveal = new double[16];
        double[] exitReveal = new double[16];        // overworld-reveal ramp 0→1 across the exit crossfade
        double[] exitDisperse = new double[16];      // mirror-disperse ramp 1→0 across the exit crossfade
        boolean any = false;
        boolean anyInBand = false;
        boolean anyLead = false;
        boolean anyExit = false;
        for (int dx = 0; dx < 16; dx++) {
            // The special bands/zones are disjoint by construction. In-band columns get the full mirror;
            // entry lead-in columns (before the band) get a Y-windowed partial mirror; exit-fade columns
            // (right after the band) get the dual island crossfade — the mirror disperses into shrinking,
            // spreading islands while the original overworld fades back in over the void.
            int worldX = chunkMinX + dx;
            inBand[dx] = UpsideDownBand.isInBand(level, worldX);
            if (!inBand[dx]) {
                inLead[dx] = UpsideDownBand.isInEntryLead(level, worldX);
                if (inLead[dx]) {
                    leadReveal[dx] = UpsideDownBand.entryRevealRamp(level, worldX);
                } else {
                    inExit[dx] = UpsideDownBand.isInExitFade(level, worldX);
                    if (inExit[dx]) {
                        exitReveal[dx] = UpsideDownBand.exitOwReveal(level, worldX);
                        exitDisperse[dx] = UpsideDownBand.exitMirrorDisperse(level, worldX);
                    }
                }
            }
            if (inBand[dx]) anyInBand = true;
            if (inLead[dx]) anyLead = true;
            if (inExit[dx]) anyExit = true;
            if (inBand[dx] || inLead[dx] || inExit[dx]) any = true;
        }
        if (!any) return;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        long seed = data.getGenerationSeed();          // drives the lead-in fade dither (matches the void erosion noise)
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

        // Per-column reveal window. In-band columns get the full mirror (extent = whole build range, so
        // no target Y is ever clipped) and always take the bedrock roof. Lead-in columns get a Y-window
        // that grows outward from the train gap as the reveal ramp climbs (UpsideDownBand.revealYExtent),
        // and only take the roof once that window has reached roofY — "no bedrock until the Y reaches it".
        int[] extentCol = new int[16];
        boolean[] roofCol = new boolean[16];
        boolean[] exitFloorClear = new boolean[16];   // exit columns whose overworld hasn't coalesced → keep open void
        for (int dx = 0; dx < 16; dx++) {
            if (inBand[dx]) {
                extentCol[dx] = maxY;                                  // unbounded — never clips
                roofCol[dx] = true;
            } else if (inLead[dx]) {
                int extent = UpsideDownBand.revealYExtent(leadReveal[dx], mirror, ceilingGap, floorGap, roofY, minY);
                extentCol[dx] = extent;
                roofCol[dx] = mirror + ceilingGap + extent >= roofY;   // ceiling grown up to the lid
            } else if (inExit[dx]) {
                extentCol[dx] = maxY;                                  // no Y clip; the noise island gate thins the ceiling
                // Bedrock lid recedes as the mirror disperses; floor returns once the overworld coalesces.
                roofCol[dx] = roofInvert && exitDisperse[dx] >= DungeonTrainCommonConfig.UPSIDE_DOWN_EXIT_ROOF_RECEDE;
                exitFloorClear[dx] = exitReveal[dx] < DungeonTrainCommonConfig.UPSIDE_DOWN_EXIT_FLOOR_RETURN;
            }
        }

        // Corridor geometry for the in-band track re-lay below. The corridor is NO LONGER preserved from
        // the mirror — it is flipped like the scenery, then TrackGenerator.layFlippedCorridor carves the
        // tube + stamps the rails onto the mirrored terrain (TrackBedFeature skips in-band chunks, so there
        // are no during-gen rails here to flip). This is what makes the train ride through the upside-down
        // world instead of a preserved plain tube.
        TrackGeometry g = TrackGeometry.from(data.dims(), trainY);
        int bedY = g.bedY();                            // drives the exit overworld-reveal depth weighting

        BlockState[] col = new BlockState[maxY - minY];
        boolean changed = false;

        for (int dz = 0; dz < 16; dz++) {
            int worldZ = chunkMinZ + dz;
            for (int dx = 0; dx < 16; dx++) {
                if (!inBand[dx] && !inLead[dx] && !inExit[dx]) continue;
                // Lead-in columns (not yet fully in-band) get a Y-WINDOWED, noise-dithered partial
                // mirror: within the window the reveal probability falls from 1 at track level to 0 at
                // the growing edge, so the whole fade is a clumpy dissolve to void (see the clip below).
                boolean windowed = inLead[dx];
                int extent = extentCol[dx];
                int worldX = chunkMinX + dx;
                // Exit-fade columns keep the open-void underside only until the overworld has coalesced.
                boolean clearFloor = inBand[dx] || inLead[dx] || (inExit[dx] && exitFloorClear[dx]);

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

                    // Entry lead-in: noise-dither the reveal across a growing Y-window centred on the
                    // train gap. Distance d outward from the gap edge (ceiling above, hang below); beyond
                    // the window → void, otherwise reveal with probability falling 1→0 across the full
                    // window height (coherentNoise, the void-erosion primitive, for a matching clumpy
                    // texture). So the upside-down terrain dissolves out of the void from track level up,
                    // over the full length of the fade — not a hard horizontal cut.
                    if (windowed && ns != AIR) {
                        int d = (y >= mirror + ceilingGap) ? y - (mirror + ceilingGap)   // ceiling side
                                                           : (mirror - floorGap) - y;    // hang side (gap interior already air)
                        if (d > extent) {
                            ns = AIR;                                                     // beyond the window
                        } else if (extent > 0) {
                            double p = 1.0 - (double) d / extent;                         // 1 at track level → 0 at the edge
                            if (Disintegration.coherentNoise(seed, worldX, y, worldZ) >= p) ns = AIR;
                        }
                        // extent == 0: only the d == 0 innermost row survives (thin track-level slab).
                    }

                    // Exit crossfade: the reflected mirror candidate (ns) disperses into shrinking,
                    // spreading islands while the ORIGINAL overworld column fades back in as islands over
                    // the void. Both are pulled from the pristine snapshot; the overworld wins on Y-overlap
                    // so the returning ground always reads on top of a dispersing remnant.
                    if (inExit[dx]) {
                        // (a) Disperse the mirror: keep the reflected block only inside a surviving island.
                        // As disperse falls 1→0 the low-noise survivor set shrinks and separates (the
                        // reverse of the entry dither), so mirror islands get smaller and further apart.
                        // Sampled at EXIT_ISLAND_NOISE_SCALE for larger, further-apart islands than the
                        // fine-grained entry dither.
                        if (ns != AIR && Disintegration.coherentNoise(seed, worldX, y, worldZ, EXIT_ISLAND_NOISE_SCALE) >= exitDisperse[dx]) {
                            ns = AIR;
                        }
                        // (b) Reveal the overworld by running the End void-erosion IN REVERSE (ramp =
                        // 1 − reveal): at reveal 0 all void, at reveal 1 the solid world returns; the depth
                        // boost makes surface islands appear first and fill downward — "small, then bigger".
                        BlockState ow = col[y - minY];
                        if (ow != AIR) {
                            if (ow.hasBlockEntity()) {
                                ns = ow;                                   // native BE — leave it exactly in place (identity Y)
                            } else {
                                double pRemove = Disintegration.removalProbabilityFromRamp(1.0 - exitReveal[dx], y, bedY);
                                if (Disintegration.coherentNoise(seed ^ OW_ISLAND_SALT, worldX, y, worldZ, EXIT_ISLAND_NOISE_SCALE) >= pRemove) {
                                    if (ow.getBlock() instanceof LiquidBlock) {
                                        // Returning water becomes a static SOURCE (frozen in-zone by
                                        // FlowingFluidUpsideDownMixin) so it hangs on the island instead of
                                        // pouring off it into the void; other fluids (lava) drop to air.
                                        ns = ow.getFluidState().is(FluidTags.WATER) ? Blocks.WATER.defaultBlockState() : AIR;
                                    } else {
                                        BlockState stable = FallingBlockAnchor.stableEquivalent(ow);
                                        ns = stable != null ? stable : ow; // survives → returning overworld (fallables anchored)
                                    }
                                }
                            }
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

                // Open the underside across every participating column that should hang over void (band +
                // lead-in, and exit columns until their overworld coalesces): remove the surface-rule
                // bedrock left at minY (the mirror never touches that row). Once an exit column's overworld
                // has returned (clearFloor false) the ordinary floor is left in place.
                if (roofInvert && clearFloor) {
                    LevelChunkSection fSec = chunk.getSection(floorSectionIdx);
                    if (!fSec.getBlockState(dx, floorLocalY, dz).isAir()) {
                        fSec.setBlockState(dx, floorLocalY, dz, AIR, false);
                        changed = true;
                    }
                }
            }
        }

        // Bedrock roof: a continuous lid stamped at roofY directly above the reflected ceiling, over
        // every column whose ceiling has reached it (roofCol[dx]) — always for in-band columns, and for
        // a lead-in column only once its growing Y-window has climbed up to roofY ("no bedrock until the
        // Y level reaches it"). Independent of the corridor Z-skip; only when the lid lands above the
        // train and inside the build range.
        if (roofInvert && roofY > mirror && roofY < maxY) {
            BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
            int roofSectionIdx = chunk.getSectionIndex(roofY);
            LevelChunkSection roofSec = chunk.getSection(roofSectionIdx);
            int roofLocalY = roofY - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(roofSectionIdx));
            for (int dz = 0; dz < 16; dz++) {
                int worldZ = chunkMinZ + dz;
                for (int dx = 0; dx < 16; dx++) {
                    if (!roofCol[dx]) continue;
                    BlockState cur = roofSec.getBlockState(dx, roofLocalY, dz);
                    if (cur == bedrock) continue;
                    if (cur.hasBlockEntity()) {
                        chunk.removeBlockEntity(new BlockPos(chunkMinX + dx, roofY, worldZ));
                    }
                    roofSec.setBlockState(dx, roofLocalY, dz, bedrock, false);
                    changed = true;
                }
            }
        }

        // Lay the corridor into the now-composited terrain — the counterpart to TrackBedFeature (which
        // skips band, lead-in AND exit-fade chunks): carve the carriage tube and stamp the authored
        // bed/rails, so the train runs through the upside-down world instead of a preserved tube. Runs
        // after the mirror/roof/exit passes (same handler → strictly ordered, so it wins the corridor
        // Z-strip). Gated on band OR lead-in OR exit: the exit fade is laid flipped across its whole
        // length — over void on the mirror-dominant side (the mirror encloses the tube) and through the
        // returning overworld on the far side (a carved cutting) — with the stamped bed giving the train a
        // continuous floor either way (bed/rails stay at bedY/railY, so the track is continuous).
        if ((anyInBand || anyLead || anyExit) && chunk instanceof LevelChunk levelChunk) {
            TrackGenerator.layFlippedCorridor(level, levelChunk, data.dims(), pos.x, pos.z, g);
            changed = true;
        }
        if (changed) chunk.setUnsaved(true);
    }
}
