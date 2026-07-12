package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;

/**
 * The upside-down band's vertical mirror, split into an off-thread <b>compute</b> and a main-thread
 * <b>apply</b> so the expensive half can run during world generation instead of at chunk load.
 *
 * <p>{@link #compute(ServerLevel, ChunkAccess)} reads a chunk's finished overworld terrain and returns
 * an immutable {@link MirrorPlan} — the exact list of block writes the mirror would make (reflected
 * ceiling/hang, bedrock roof, floor-clear, exit crossfade, entry-lead window). It never mutates the
 * chunk, so it is safe to run on the worldgen worker thread at the {@code SPAWN} generation step (a
 * read is read/read-safe against the light engine). {@link #apply(ChunkAccess, MirrorPlan)} replays
 * the plan through the raw {@link LevelChunkSection#setBlockState} primitive — the same Sable-safe,
 * light-skipping write path {@code WorldUpsideDownEvents} used inline — and stays on the main thread at
 * {@link Load}, where the palette write is proven safe.
 *
 * <p>The split is behaviour-preserving. The band's input terrain is invariant between {@code SPAWN}
 * and Load — erosion is scoped to the End band and nothing else mutates in-band columns — so the
 * pristine snapshot {@code compute} reads equals the live chunk {@code apply} writes into. {@code apply}
 * re-reads each target cell and keeps the original {@code cur == ns} no-op skip and block-entity
 * removal, so a stale or missing plan can never write the wrong block. {@link #mirror(ServerLevel,
 * ChunkAccess)} does both in one call — the inline fallback used when precompute is off or the plan was
 * evicted. The reflection maths, water/fallable handling, and noise dithers are lifted verbatim from
 * the old {@code WorldUpsideDownEvents.onChunkLoad}; see that class's javadoc for the full model. The
 * flipped train corridor is <em>not</em> part of the mirror — it is laid separately at Load (it needs a
 * {@code LevelChunk} and is negligibly cheap).
 */
public final class UpsideDownMirror {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Decorrelates the overworld-reveal island mask from the mirror-disperse mask in the exit crossfade. */
    private static final long OW_ISLAND_SALT = 0x9E3779B97F4A7C15L;

    /** Enlarges the exit-crossfade island noise (bigger islands, proportionally bigger gaps). */
    private static final int EXIT_ISLAND_NOISE_SCALE = 4;

    private UpsideDownMirror() {}

    // Local-position packing for a MirrorPlan entry: dx/dz in [0,15] (4 bits each), y stored as an
    // offset from the chunk's minY in the remaining high bits (always ≥ 0, so >>> unpacks it). Shared
    // by the writer (PlanBuilder) and reader (apply) so they can never disagree.
    static int pack(int dx, int y, int dz, int minY) {
        return (dx & 0xF) | ((dz & 0xF) << 4) | ((y - minY) << 8);
    }

    static int unpackDx(int packed) {
        return packed & 0xF;
    }

    static int unpackDz(int packed) {
        return (packed >> 4) & 0xF;
    }

    static int unpackY(int packed, int minY) {
        return (packed >>> 8) + minY;
    }

    /**
     * An immutable, compact record of the block writes the mirror would apply to one chunk, in the
     * exact order the old inline handler issued them (so replaying is last-write-wins identical). Each
     * entry is a packed local position ({@code dx | dz<<4 | (y-minY)<<8}) and its target state.
     */
    public static final class MirrorPlan {
        private final int minY;
        private final int[] packed;
        private final BlockState[] states;

        private MirrorPlan(int minY, int[] packed, BlockState[] states) {
            this.minY = minY;
            this.packed = packed;
            this.states = states;
        }

        /** Number of recorded writes (may be 0 for a band-relevant but all-air chunk). */
        public int size() {
            return packed.length;
        }
    }

    /** Accumulates writes during {@link #compute}; frozen into a {@link MirrorPlan}. */
    private static final class PlanBuilder {
        private final int minY;
        private final IntArrayList packed = new IntArrayList();
        private final ObjectArrayList<BlockState> states = new ObjectArrayList<>();

        PlanBuilder(int minY) {
            this.minY = minY;
        }

        void add(int dx, int y, int dz, BlockState state) {
            packed.add(pack(dx, y, dz, minY));
            states.add(state);
        }

        MirrorPlan freeze() {
            return new MirrorPlan(minY, packed.toIntArray(), states.toArray(new BlockState[0]));
        }
    }

    /**
     * Compute the mirror's writes for {@code chunk} without mutating it. Returns {@code null} when the
     * chunk is not in the upside-down band / entry-lead / exit-fade (band off, or before the first
     * band, or no in-band column) — i.e. exactly when the old handler returned early. Otherwise returns
     * a (possibly empty) plan; a non-null return also signals the caller to lay the flipped corridor.
     */
    public static MirrorPlan compute(ServerLevel level, ChunkAccess chunk) {
        long startX = UpsideDownBand.startX(level);
        if (startX == UpsideDownBand.OFF) return null;

        var pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        int chunkMinZ = pos.getMinBlockZ();
        if (chunkMinX + 15 < startX) return null; // before the first band

        boolean[] inBand = new boolean[16];
        boolean[] inLead = new boolean[16];
        boolean[] inExit = new boolean[16];
        double[] leadReveal = new double[16];
        double[] exitReveal = new double[16];        // overworld-reveal ramp 0→1 across the exit crossfade
        double[] exitDisperse = new double[16];      // mirror-disperse ramp 1→0 across the exit crossfade
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
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
            if (inBand[dx] || inLead[dx] || inExit[dx]) any = true;
        }
        if (!any) return null;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        long seed = data.getGenerationSeed();          // drives the lead-in fade dither (matches the void erosion noise)
        int trainY = data.getTrainY();
        int mirror = trainY + DungeonTrainCommonConfig.getUpsideDownMirrorPlaneOffset();
        int ceilingGap = Math.max(0, DungeonTrainCommonConfig.getUpsideDownCeilingGap());
        int floorGap = Math.max(0, DungeonTrainCommonConfig.getUpsideDownFloorGap());
        double exitNoiseSkipEps = DungeonTrainCommonConfig.getUpsideDownExitNoiseSkipEpsilon();
        int maxCeilingHeight = DungeonTrainCommonConfig.getUpsideDownMaxCeilingHeight();
        int ceilCapY = maxCeilingHeight > 0 ? mirror + ceilingGap + maxCeilingHeight : Integer.MAX_VALUE;

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();          // exclusive
        int floorGuard = minY;                          // never read or write the bedrock row

        boolean roofInvert = DungeonTrainCommonConfig.isUpsideDownBedrockRoof();
        int roofY = UpsideDownBand.bedrockRoofY(mirror, ceilingGap, minY, maxY);
        roofY = UpsideDownBand.cappedRoofY(roofY, mirror, ceilingGap, maxCeilingHeight);
        int floorSectionIdx = chunk.getSectionIndex(minY);
        int floorLocalY = minY - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(floorSectionIdx));

        int[] extentCol = new int[16];
        boolean[] roofCol = new boolean[16];
        boolean[] exitFloorClear = new boolean[16];   // exit columns whose overworld hasn't coalesced → keep open void
        boolean[] exitMirrorKeepAll = new boolean[16]; // disperse gate provably keeps → skip its sample
        boolean[] exitMirrorDropAll = new boolean[16]; // disperse gate provably drops → skip its sample (→ AIR)
        boolean[] exitOwKeepAll = new boolean[16];     // reveal gate provably keeps overworld → skip its sample
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
                roofCol[dx] = roofInvert && exitDisperse[dx] >= DungeonTrainCommonConfig.UPSIDE_DOWN_EXIT_ROOF_RECEDE;
                exitFloorClear[dx] = exitReveal[dx] < DungeonTrainCommonConfig.UPSIDE_DOWN_EXIT_FLOOR_RETURN;
                exitMirrorKeepAll[dx] = UpsideDownBand.exitMirrorKeepsAll(exitDisperse[dx], exitNoiseSkipEps);
                exitMirrorDropAll[dx] = !exitMirrorKeepAll[dx]
                        && UpsideDownBand.exitMirrorDropsAll(exitDisperse[dx], exitNoiseSkipEps);
                exitOwKeepAll[dx] = UpsideDownBand.exitOverworldKeepsAll(exitReveal[dx], exitNoiseSkipEps);
            }
        }

        TrackGeometry g = TrackGeometry.from(data.dims(), trainY);
        int bedY = g.bedY();                            // drives the exit overworld-reveal depth weighting

        PlanBuilder plan = new PlanBuilder(minY);
        BlockState[] col = new BlockState[maxY - minY];

        for (int dz = 0; dz < 16; dz++) {
            int worldZ = chunkMinZ + dz;
            for (int dx = 0; dx < 16; dx++) {
                if (!inBand[dx] && !inLead[dx] && !inExit[dx]) continue;
                boolean windowed = inLead[dx];
                int extent = extentCol[dx];
                int worldX = chunkMinX + dx;
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
                if (srcMax < srcMin) continue; // empty column — nothing to mirror (floor-clear skipped, as before)

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
                                if (s.getFluidState().is(FluidTags.WATER)) {
                                    ns = Blocks.WATER.defaultBlockState();
                                }
                            } else {
                                BlockState stable = FallingBlockAnchor.stableEquivalent(s);
                                ns = stable != null ? stable : s;
                            }
                        }
                    }

                    // Entry lead-in: noise-dither the reveal across a growing Y-window centred on the gap.
                    if (windowed && ns != AIR) {
                        int d = (y >= mirror + ceilingGap) ? y - (mirror + ceilingGap)   // ceiling side
                                                           : (mirror - floorGap) - y;    // hang side
                        if (d > extent) {
                            ns = AIR;                                                     // beyond the window
                        } else if (extent > 0) {
                            double p = 1.0 - (double) d / extent;                         // 1 at track level → 0 at the edge
                            if (Disintegration.coherentNoise(seed, worldX, y, worldZ) >= p) ns = AIR;
                        }
                    }

                    // Ceiling cap (STRETCH): truncate the reflected ceiling above the cap.
                    if (ns != AIR && y > ceilCapY) ns = AIR;

                    // Exit crossfade: mirror disperses into islands while the overworld fades back in.
                    if (inExit[dx]) {
                        if (ns != AIR) {
                            if (exitMirrorDropAll[dx]) {
                                ns = AIR;
                            } else if (!exitMirrorKeepAll[dx]) {
                                if (Disintegration.coherentNoise(seed, worldX, y, worldZ, EXIT_ISLAND_NOISE_SCALE) >= exitDisperse[dx]) {
                                    ns = AIR;
                                }
                            }
                        }
                        BlockState ow = col[y - minY];
                        if (ow != AIR) {
                            if (ow.hasBlockEntity()) {
                                ns = ow;                                   // native BE — leave it exactly in place (identity Y)
                            } else {
                                boolean place = exitOwKeepAll[dx];
                                if (!place) {
                                    double pRemove = Disintegration.removalProbabilityFromRamp(1.0 - exitReveal[dx], y, bedY);
                                    place = Disintegration.coherentNoise(seed ^ OW_ISLAND_SALT, worldX, y, worldZ, EXIT_ISLAND_NOISE_SCALE) >= pRemove;
                                }
                                if (place) {
                                    if (ow.getBlock() instanceof LiquidBlock) {
                                        ns = ow.getFluidState().is(FluidTags.WATER) ? Blocks.WATER.defaultBlockState() : AIR;
                                    } else {
                                        BlockState stable = FallingBlockAnchor.stableEquivalent(ow);
                                        ns = stable != null ? stable : ow; // survives → returning overworld (fallables anchored)
                                    }
                                }
                            }
                        }
                    }

                    // Record only genuine changes: the pristine value at the target cell is col[y-minY],
                    // which equals the live cur apply() will re-check — so this filter is exactly the old
                    // handler's cur == ns no-op skip, precomputed off-thread.
                    if (ns != col[y - minY]) plan.add(dx, y, dz, ns);
                }

                // Open the underside for columns that should hang over void (band + lead-in + not-yet-
                // returned exit). Recorded unconditionally; apply()'s cur == ns skip drops the no-op when
                // minY is already air, matching the old !isAir() guard.
                if (roofInvert && clearFloor) plan.add(dx, minY, dz, AIR);
            }
        }

        // Bedrock roof: a continuous lid at roofY over every column whose ceiling reached it. Recorded
        // last (after every column's reflected ceiling) so a later duplicate at roofY wins — the same
        // ordering the old handler's trailing roof loop produced.
        if (roofInvert && roofY > mirror && roofY < maxY) {
            BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    if (!roofCol[dx]) continue;
                    plan.add(dx, roofY, dz, bedrock);
                }
            }
        }

        return plan.freeze();
    }

    /**
     * Replay a {@link MirrorPlan} onto {@code chunk} on the main thread, through the raw section-write
     * path (no light / neighbour / heightmap updates — the band's client atmosphere handles lighting).
     * Re-reads each target cell so the original {@code cur == ns} no-op skip and block-entity removal
     * are preserved; a stale plan can therefore never write the wrong block. Marks the chunk unsaved iff
     * anything changed.
     */
    public static void apply(ChunkAccess chunk, MirrorPlan plan) {
        if (plan == null || plan.size() == 0) return;
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        boolean changed = false;
        for (int i = 0; i < plan.packed.length; i++) {
            int p = plan.packed[i];
            int dx = unpackDx(p);
            int dz = unpackDz(p);
            int y = unpackY(p, plan.minY);
            BlockState ns = plan.states[i];

            int sIdx = chunk.getSectionIndex(y);
            LevelChunkSection sec = chunk.getSection(sIdx);
            int ly = y - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
            BlockState cur = sec.getBlockState(dx, ly, dz);
            if (cur == ns) continue;                 // no-op (block states are interned singletons)
            if (cur.hasBlockEntity()) {
                chunk.removeBlockEntity(new BlockPos(chunkMinX + dx, y, chunkMinZ + dz));
            }
            sec.setBlockState(dx, ly, dz, ns, false);
            changed = true;
        }
        if (changed) chunk.setUnsaved(true);
    }

    /** Inline convenience: compute + apply in one call (the precompute-off / cache-miss fallback). */
    public static void mirror(ServerLevel level, ChunkAccess chunk) {
        MirrorPlan plan = compute(level, chunk);
        if (plan != null) apply(chunk, plan);
    }
}
