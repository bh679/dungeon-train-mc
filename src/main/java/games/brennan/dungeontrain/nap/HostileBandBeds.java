package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.joml.Vector3d;

/**
 * Which hostile core a bed or respawn anchor stands in <i>right now</i>: the real Nether or End, or
 * DT's Nether / End band core inside the overworld. Beds explode in either ({@code BedBlockMixin});
 * respawn anchors work in the Nether and explode everywhere else ({@code RespawnAnchorBlockMixin}).
 *
 * <p>In the overworld the answer is the <b>baked biome</b> at the block, not a live band formula, so
 * it follows what the world generated and the player sees: only the Nether core carries an
 * {@code IS_NETHER} biome and only the End core an {@code IS_END} one. A block in a carriage is read
 * at the carriage's current world position instead, because Sable freezes a sub-level's biome at
 * assembly (#1448): a bed that rode into the Nether explodes, and one that rode back out sleeps.</p>
 */
public final class HostileBandBeds {

    public enum Core { NONE, NETHER, END }

    private HostileBandBeds() {}

    public static Core coreAt(ServerLevel level, BlockPos pos) {
        if (level.dimension() == Level.NETHER) return Core.NETHER;
        if (level.dimension() == Level.END) return Core.END;
        Holder<Biome> biome = level.getBiome(biomeSamplePos(level, pos));
        if (biome.is(BiomeTags.IS_NETHER)) return Core.NETHER;
        if (biome.is(BiomeTags.IS_END)) return Core.END;
        return Core.NONE;
    }

    /** A bed here explodes rather than sleeping. */
    public static boolean bedExplodes(ServerLevel level, BlockPos pos) {
        return !level.dimensionType().bedWorks() || coreAt(level, pos) != Core.NONE;
    }

    /** A Respawn Obelisk here works (it offers a nap) only in an End core; anywhere else it explodes. */
    public static boolean obeliskWorks(ServerLevel level, BlockPos pos) {
        return coreAt(level, pos) == Core.END;
    }

    /** A respawn anchor here works as in the Nether; anywhere else a charged one explodes. */
    public static boolean anchorWorks(ServerLevel level, BlockPos pos) {
        return coreAt(level, pos) == Core.NETHER;
    }

    /**
     * Where to read the biome for a block at {@code pos}: the position itself on the ground, or, for a
     * plot-space block inside a Sable carriage, the carriage's current world-space position of it.
     * {@code shipToWorld} mutates its argument, so it is handed a throwaway vector.
     */
    private static BlockPos biomeSamplePos(ServerLevel level, BlockPos pos) {
        ManagedShip ship = Shipyards.of(level).findAt(pos);
        if (ship == null) return pos;
        Vector3d world = ship.shipToWorld(new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        return BlockPos.containing(world.x, world.y, world.z);
    }
}
