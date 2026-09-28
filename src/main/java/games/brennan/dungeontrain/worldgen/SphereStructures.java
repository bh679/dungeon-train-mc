package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The structure a structure-rolled sphere is built around — one per sphere, identical in every chunk
 * the sphere spans, so its pieces meet across chunk seams.
 *
 * <p>The pick is deterministic in the world seed and the sphere: a draw from the structures whose own
 * biome list admits the source dimension's biome at the sphere's source centre, generated at the
 * sphere's centre chunk. A start whose pieces miss the rows the sphere shows is moved so it sits inside
 * them — a fortress or bastion otherwise lands at whatever Y its own placement wants. The (possibly
 * moved) start is memoised per sphere, so the jigsaw is assembled once, not once per chunk; each chunk
 * sample then registers it and vanilla's decoration pass places the pieces that fall in that chunk.</p>
 */
final class SphereStructures {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int ATTEMPTS = 12;
    private static final int MAX_MEMO = 512;

    private static final ConcurrentHashMap<Long, Optional<StructureStart>> STARTS = new ConcurrentHashMap<>();

    private SphereStructures() {}

    static void clear() {
        STARTS.clear();
    }

    /** Register the sphere's structure on {@code chunk} so the decoration pass places its pieces here. */
    static void register(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState random,
                         ProtoChunk chunk, SphereField.Sphere sphere, long seed) {
        Optional<StructureStart> start = STARTS.get(sphere.id());
        if (start == null) {
            if (STARTS.size() >= MAX_MEMO) STARTS.clear();
            start = STARTS.computeIfAbsent(sphere.id(), id -> build(level, generator, random, sphere, seed));
        }
        start.ifPresent(s -> {
            chunk.setStartForStructure(s.getStructure(), s);
            chunk.addReferenceForStructure(s.getStructure(), chunk.getPos().toLong());
        });
    }

    private static Optional<StructureStart> build(ServerLevel level, NoiseBasedChunkGenerator generator,
                                                  RandomState random, SphereField.Sphere sphere, long seed) {
        int sourceCentreY = sphere.sourceY(sphere.cy());
        List<Structure> candidates = fitting(level, generator, random, sphere, sourceCentreY);
        if (candidates.isEmpty()) return Optional.empty();
        Random rng = new Random(seed ^ (sphere.id() * 0xC2B2AE3D27D4EB4FL));
        ChunkPos centre = new ChunkPos(sphere.cx() >> 4, sphere.cz() >> 4);
        long worldSeed = level.getSeed();
        for (int attempt = 0; attempt < ATTEMPTS && !candidates.isEmpty(); attempt++) {
            Structure structure = candidates.remove(rng.nextInt(candidates.size()));
            StructureStart start;
            try {
                start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(), random,
                        level.getStructureManager(), worldSeed, centre, /*references*/ 0, level, biome -> true);
            } catch (Throwable t) {
                continue;
            }
            if (!start.isValid()) continue;
            fitIntoSphere(start, sphere, sourceCentreY);
            LOGGER.debug("[DungeonTrain] Sphere at ({}, {}, {}) built around {}", sphere.cx(), sphere.cy(),
                    sphere.cz(), level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(structure));
            return Optional.of(start);
        }
        return Optional.empty();
    }

    /** Every structure whose biome list admits the biome at the sphere's source centre. */
    private static List<Structure> fitting(ServerLevel level, NoiseBasedChunkGenerator generator,
                                           RandomState random, SphereField.Sphere sphere, int sourceCentreY) {
        Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(sphere.cx()),
                QuartPos.fromBlock(sourceCentreY), QuartPos.fromBlock(sphere.cz()), random.sampler());
        List<Structure> out = new ArrayList<>();
        for (Structure structure : level.registryAccess().registryOrThrow(Registries.STRUCTURE)) {
            if (structure.biomes().contains(biome)) out.add(structure);
        }
        return out;
    }

    /**
     * Move the start vertically when its pieces miss the sphere's source rows, so its base sits a third
     * of the way up from the sphere's bottom — inside the ball, with room above for what it builds.
     */
    private static void fitIntoSphere(StructureStart start, SphereField.Sphere sphere, int sourceCentreY) {
        BoundingBox span = spanOf(start);
        int lo = sourceCentreY - sphere.r(), hi = sourceCentreY + sphere.r();
        if (span.maxY() >= lo && span.minY() <= hi) return;
        int lift = (lo + sphere.r() * 2 / 3) - span.minY();
        start.getPieces().forEach(piece -> piece.move(0, lift, 0));
    }

    /** The box the pieces occupy, computed (the start's own box is memoised and goes stale on a move). */
    private static BoundingBox spanOf(StructureStart start) {
        BoundingBox span = null;
        for (StructurePiece piece : start.getPieces()) {
            span = span == null ? piece.getBoundingBox() : span.encapsulate(piece.getBoundingBox());
        }
        return span == null ? start.getBoundingBox() : span;
    }
}
