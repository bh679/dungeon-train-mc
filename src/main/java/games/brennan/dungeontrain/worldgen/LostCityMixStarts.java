package games.brennan.dungeontrain.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import java.util.List;

/**
 * Makes every mix-zone chunk that picked the Lost City ({@link MixBand#picksLostCity}) a start chunk of DT's
 * Lost City structure set, so the pick always shows a building, cut to the floating chunk by the void around it.
 *
 * <p>The set's own grid ({@code random_spread}, spacing 6) offers one start per 36 chunks, seeded from the level
 * seed, which the pick never sees; on its own a pick almost never held a building. {@code ChunkGeneratorMixLostCityMixin}
 * names the level whose structures are being created on this thread, and {@code StructurePlacementMixLostCityMixin}
 * answers yes for the set's placement in a picked chunk. The mix zone's rule against structure starts
 * ({@code StructureBandBiomeFilterMixin}) stands aside for Lost City structures there, and the start goes through
 * vanilla's usual loop: the era veto ({@link LostCityStructures#allowedAt}) allows it on the pick's shifted cycle,
 * and a building whose biomes refuse the chunk hands over to the set's next entry. {@code StructureMixCentreMixin}
 * then centres the building on the chunk ({@link #centre}), so the part left standing is its middle.</p>
 *
 * <p>Only the structure pass asks: {@code /locate} and other placement checks keep the set's own grid.</p>
 */
public final class LostCityMixStarts {

    private LostCityMixStarts() {}

    /** {@code salt} of {@code data/dungeontrain/worldgen/structure_set/lost_city.json}; a test pins the two together. */
    public static final int SET_SALT = 61837201;

    private static final ThreadLocal<ServerLevel> LEVEL = new ThreadLocal<>();

    /** The level whose chunk's structures this thread is creating; {@code null} clears it. */
    public static void creating(ServerLevel level) {
        if (level == null) LEVEL.remove();
        else LEVEL.set(level);
    }

    /** Whether a placement with {@code salt} must start in chunk {@code (chunkX, chunkZ)} of the level being created. */
    public static boolean forcesStart(int salt, int chunkX, int chunkZ) {
        return salt == SET_SALT && picksLostCity(chunkX, chunkZ);
    }

    /**
     * Whether chunk {@code (chunkX, chunkZ)} of the level whose structures this thread is creating picked the Lost
     * City — where the mix zone's no-structure rule lets a Lost City building start. {@code false} outside
     * {@code createStructures}.
     */
    public static boolean picksLostCity(int chunkX, int chunkZ) {
        ServerLevel level = LEVEL.get();
        return level != null && MixBand.picksLostCity(level, chunkX, chunkZ);
    }

    /**
     * Moves {@code pieces} sideways so their combined box is centred on chunk {@code pos}: a jigsaw start grows out
     * from the chunk's corner, and only the part inside the chunk survives the void around it.
     */
    public static void centre(List<StructurePiece> pieces, ChunkPos pos) {
        if (pieces.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (StructurePiece p : pieces) {
            BoundingBox b = p.getBoundingBox();
            minX = Math.min(minX, b.minX());
            minZ = Math.min(minZ, b.minZ());
            maxX = Math.max(maxX, b.maxX());
            maxZ = Math.max(maxZ, b.maxZ());
        }
        int dx = pos.getMiddleBlockX() - Math.floorDiv(minX + maxX + 1, 2);
        int dz = pos.getMiddleBlockZ() - Math.floorDiv(minZ + maxZ + 1, 2);
        if (dx == 0 && dz == 0) return;
        for (StructurePiece p : pieces) p.move(dx, 0, dz);
    }
}
