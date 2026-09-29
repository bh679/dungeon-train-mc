package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.List;

/**
 * Where two Lost City buildings stand on the same ground.
 *
 * <p>DT's dense placement grid sits on top of the mod's own sets, so footprints overlap freely. Every
 * structure start is registered before any piece places, so during placement the chunk's structure
 * references name every Lost City piece whose box reaches a position — the piece being placed among them.
 * A position inside {@link #MIN_PIECES} or more such boxes is an overlap: there
 * {@link LostCityGroundProcessor} lets the template's air yield to whatever the earlier building left,
 * so the two meld instead of the later one carving its rooms through the first.</p>
 *
 * <p>Reads only the chunk being decorated and the start chunks it references, which the features step
 * already holds ({@code StructureManager#startsForStructure} is vanilla's own decoration path).</p>
 */
public final class LostCityOverlap {

    private LostCityOverlap() {}

    /** Lost City pieces whose box must contain a position for it to count as an overlap. */
    static final int MIN_PIECES = 2;

    /**
     * Whether {@code at} lies inside the boxes of two or more Lost City pieces. Only a world-gen region can
     * answer (it carries the structure starts); any other reader — a unit test, a later-step caller — is
     * never an overlap.
     */
    public static boolean overlapped(LevelReader world, BlockPos at) {
        if (!(world instanceof WorldGenRegion region)) return false;
        StructureManager manager = region.getLevel().structureManager().forWorldGenRegion(region);
        Registry<Structure> registry = region.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<StructureStart> starts = manager.startsForStructure(new ChunkPos(at),
                structure -> LostCityStructures.isLostCityStructure(registry.getKey(structure)));
        return piecesContaining(starts, at) >= MIN_PIECES;
    }

    /** How many pieces of {@code starts} have a bounding box containing {@code at}. */
    static int piecesContaining(List<StructureStart> starts, BlockPos at) {
        int count = 0;
        for (StructureStart start : starts) {
            for (StructurePiece piece : start.getPieces()) {
                if (piece.getBoundingBox().isInside(at)) count++;
            }
        }
        return count;
    }
}
