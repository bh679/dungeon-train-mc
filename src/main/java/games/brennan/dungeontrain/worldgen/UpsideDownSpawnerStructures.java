package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * The spawner structures that never generate in the {@link UpsideDownBand} (its entry lead-in and exit
 * fade included): dungeons, trial chambers and mineshafts.
 *
 * <p>The band is a vertical mirror, so these end up hanging in the ceiling, and while
 * {@code BandMobSpawnEvents} cancels every natural spawn there, a spawner's mobs are not natural — they
 * would keep dropping out of the roof. Rather than generate a spawner nobody can reach and cancel its
 * output, the structure is left out. Strongholds are deliberately not on the list — the End portal and
 * the stronghold-ring logic depend on them. Wired through the same seams as {@link LegacyUnderground}.</p>
 */
public final class UpsideDownSpawnerStructures {

    private UpsideDownSpawnerStructures() {}

    /** Structures (by registry id) left out of upside-down chunks. */
    static final Set<String> STRUCTURES = Set.of(
        "minecraft:trial_chambers",
        "minecraft:mineshaft",
        "minecraft:mineshaft_mesa");

    /** Placed features (by registry id) left out of upside-down chunks. */
    static final Set<String> FEATURES = Set.of(
        "minecraft:monster_room",
        "minecraft:monster_room_deep");

    /** Whether any column of overworld chunk {@code (chunkX, chunkZ)} is in the band, its entry lead-in or exit fade. */
    public static boolean appliesTo(ServerLevel level, int chunkX, int chunkZ) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        return UpsideDownBand.isInBandEntryLeadOrExit(level, minX, minZ)
            || UpsideDownBand.isInBandEntryLeadOrExit(level, minX + 15, minZ);
    }

    public static boolean excludesStructure(ResourceLocation id) {
        return id != null && STRUCTURES.contains(id.toString());
    }

    public static boolean excludesFeature(ResourceLocation id) {
        return id != null && FEATURES.contains(id.toString());
    }
}
