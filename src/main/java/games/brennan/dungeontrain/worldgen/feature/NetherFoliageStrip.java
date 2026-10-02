package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.worldgen.ChunkPlanCache;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;

/**
 * Keeps overworld foliage (trees/leaves/flowers) out of the <b>netherrack crossfade + Nether core</b> of the
 * transition band, where green vegetation on netherrack would look wrong. The mountain STAGES are real,
 * vegetated terrain (the band's height lives in the density router, with highland biomes forced on top) — so
 * foliage is KEPT there; only the netherrack zone ({@link NetherBand#netherRampAt} {@code > 0}) is stripped.
 * Columns the End band owns ({@link DisintegrationBand#middleRampAt} {@code > 0}) are never touched, and in
 * the real-Nether <b>core</b> the Nether's own flora is kept ({@link StrippableFoliage#isNetherFlora}).
 *
 * <p><b>Compute/apply split.</b> {@link #compute} is a read-only scan that runs on the worldgen worker at the
 * {@code SPAWN} step ({@code ChunkStatusSpawnMixin}) — the earliest step that is off the main thread and
 * terrain-final (every neighbour's decoration, and so every spilled canopy, is present). Its result is stashed
 * in {@link #CACHE} and {@link #apply}'d on the main thread at {@code ChunkEvent.Load} by
 * {@code NetherTransitionEvents}, through the raw {@link LevelChunkSection#setBlockState} write (the Sable-safe,
 * light-skipping path the strip has always used). A cache miss recomputes inline at Load — identical result.
 *
 * <p><b>Why the scan is cheap now.</b> A section is skipped outright unless its palette holds a strippable
 * block ({@link LevelChunkSection#maybeHas}: a handful of palette entries instead of 4096 reads — the core's
 * netherrack/lava/air sections never get scanned), and the per-block predicates are cached per block
 * ({@link StrippableFoliage}). Stripping used to cost up to ~98k block reads × six tag checks on the server
 * thread per band chunk, the 5 s stall in the 0.983.0 lag report.</p>
 */
public final class NetherFoliageStrip {

    /** SPAWN-computed plans awaiting their Load apply (overworld-only, keyed by packed chunk pos). */
    public static final ChunkPlanCache<Plan> CACHE = new ChunkPlanCache<>();

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private NetherFoliageStrip() {}

    /**
     * Chunk-local positions to strip, each packed as {@code sectionIdx << 12 | dx << 8 | ly << 4 | dz}.
     * Immutable; {@code positions} is never empty (a chunk with nothing to strip has no plan).
     */
    public record Plan(int[] positions) {
        public Plan {
            positions = positions.clone();
        }

        public int size() {
            return positions.length;
        }
    }

    /** Pack a chunk-local block position (section index + section-local x/y/z). */
    static int pack(int sectionIdx, int dx, int ly, int dz) {
        return (sectionIdx << 12) | (dx << 8) | (ly << 4) | dz;
    }

    /**
     * Read-only scan: which blocks of {@code chunk} the strip will clear. Safe on any thread that may read the
     * chunk's sections. Returns {@code null} when the band is off, no column of the chunk is in the netherrack
     * zone, or nothing strippable is present.
     */
    public static Plan compute(ServerLevel overworld, ChunkAccess chunk) {
        long startX = NetherBand.startX(overworld);
        if (startX == NetherBand.OFF) return null;

        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        int chunkMinZ = pos.getMinBlockZ();

        boolean[] band = new boolean[16];
        boolean[] core = new boolean[16];
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            // Only the netherrack crossfade + Nether core (netherRamp > 0) — NOT the vegetated mountain
            // stages — and never a column the End band owns (End wins).
            double ramp = NetherBand.netherRampAt(overworld, worldX, chunkMinZ);
            band[dx] = ramp > 0.0 && DisintegrationBand.middleRampAt(overworld, worldX, chunkMinZ) <= 0.0;
            core[dx] = ramp >= WorldGenCycle.NETHER_CORE_THRESHOLD;
            if (band[dx]) any = true;
        }
        if (!any) return null;

        int[] out = new int[64];
        int n = 0;
        for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
            LevelChunkSection section = chunk.getSection(sIdx);
            if (!sectionMayNeedStrip(section)) continue;
            for (int dx = 0; dx < 16; dx++) {
                if (!band[dx]) continue;
                boolean keepNetherFlora = core[dx];
                for (int dz = 0; dz < 16; dz++) {
                    for (int ly = 0; ly < 16; ly++) {
                        if (!shouldStrip(section.getBlockState(dx, ly, dz), keepNetherFlora)) continue;
                        if (n == out.length) out = Arrays.copyOf(out, out.length * 2);
                        out[n++] = pack(sIdx, dx, ly, dz);
                    }
                }
            }
        }
        return n == 0 ? null : new Plan(Arrays.copyOf(out, n));
    }

    /**
     * Palette gate: {@code false} when the section is empty or its palette holds no strippable block at all,
     * so the 4096-block scan is skipped. {@link LevelChunkSection#maybeHas} is conservative — a global
     * (registry-backed) palette answers {@code true} and the section is scanned in full.
     */
    static boolean sectionMayNeedStrip(LevelChunkSection section) {
        return !section.hasOnlyAir() && section.maybeHas(StrippableFoliage::isStrippable);
    }

    /** The per-block decision; {@code keepNetherFlora} is true in the real-Nether core. */
    static boolean shouldStrip(BlockState state, boolean keepNetherFlora) {
        if (state.isAir() || !StrippableFoliage.isStrippable(state)) return false;
        return !(keepNetherFlora && StrippableFoliage.isNetherFlora(state));
    }

    /**
     * Main-thread write of a {@link Plan}. Each position is re-read and re-checked (the core rule is already
     * folded into the plan, so the re-check is the strippable test alone), so a plan computed at SPAWN stays
     * correct if anything touched the block before Load, and the apply is idempotent.
     *
     * @return whether any block was cleared (the chunk is marked unsaved when so)
     */
    public static boolean apply(ChunkAccess chunk, Plan plan) {
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        int chunkMinZ = pos.getMinBlockZ();
        boolean changed = false;
        for (int packed : plan.positions()) {
            int sIdx = packed >>> 12;
            int dx = (packed >>> 8) & 0xF;
            int ly = (packed >>> 4) & 0xF;
            int dz = packed & 0xF;
            if (sIdx >= chunk.getSectionsCount()) continue;
            LevelChunkSection section = chunk.getSection(sIdx);
            BlockState cur = section.getBlockState(dx, ly, dz);
            if (cur.isAir() || !StrippableFoliage.isStrippable(cur)) continue;
            if (cur.hasBlockEntity()) {
                int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
                chunk.removeBlockEntity(new BlockPos(chunkMinX + dx, baseY + ly, chunkMinZ + dz));
            }
            section.setBlockState(dx, ly, dz, AIR, false);
            changed = true;
        }
        if (changed) chunk.setUnsaved(true);
        return changed;
    }
}
