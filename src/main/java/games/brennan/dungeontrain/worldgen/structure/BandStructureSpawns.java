package games.brennan.dungeontrain.worldgen.structure;

import games.brennan.dungeontrain.worldgen.NetherBand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Whether a position sits inside a band structure that runs its own mob spawning — in practice, inside a
 * Nether fortress.
 *
 * <p>Two things in the band would otherwise stop a fortress spawning anything, and both are answered here.
 * {@code NetherMobSpawner} cancels every natural spawn across the band so the mountains don't fill with
 * zombies, which also eats the blazes and wither skeletons the fortress's own {@code spawn_overrides}
 * propose. And the band core sits under the <b>overworld sky</b>, where {@code Monster.checkMonsterSpawnRules}
 * refuses to spawn wither skeletons and skeletons by day — a rule that never bites in the real Nether,
 * which has no sky light at all. Inside a fortress the band should read as the Nether, so both give way.</p>
 *
 * <p>Only structures carrying a {@link MobCategory#MONSTER} spawn override qualify, which is exactly the
 * fortress: bastions, fossils and ruined portals ship empty overrides and get no special treatment. The
 * cheap band test runs first so an ordinary overworld spawn never pays for a structure lookup.</p>
 */
public final class BandStructureSpawns {

    private BandStructureSpawns() {}

    /** True if {@code pos} is inside a piece of a band structure that defines its own monster spawns. */
    public static boolean inMonsterSpawningStructure(ServerLevel level, BlockPos pos) {
        try {
            if (!level.dimension().equals(Level.OVERWORLD)) return false;
            if (NetherBand.heightRampAt(level, pos.getX()) <= 0.0) return false;
            return level.structureManager()
                    .getStructureWithPieceAt(pos, holder -> hasMonsterSpawns(holder.value()))
                    .isValid();
        } catch (Throwable t) {
            return false;   // fall back to the band's normal suppression rather than break spawning
        }
    }

    private static boolean hasMonsterSpawns(Structure structure) {
        return BandNetherStructures.isBandEligible(structure)
            && structure.spawnOverrides().containsKey(MobCategory.MONSTER);
    }
}
