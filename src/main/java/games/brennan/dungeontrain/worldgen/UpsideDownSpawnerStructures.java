package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
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
 *
 * <p>Structures that <em>do</em> generate here keep their blocks but not their residents: a mob a
 * structure template places (a village's villagers, iron golem and cats) is left out
 * ({@link #dropsTemplateEntity}). It would stand where the mirror has emptied the ground and fall. Those
 * spawns bypass {@code BandMobSpawnEvents} — the template calls {@code finalizeSpawn} directly as
 * {@code STRUCTURE}, never through {@code FinalizeSpawnEvent}. Their beds go with them in the exit
 * crossfade ({@code UpsideDownMirror.keepsNativeBlockEntity}).</p>
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

    /**
     * Whether a structure template's entity is left out: any mob whose position is in the overworld's
     * band, entry lead-in or exit fade. Non-mob template entities (item frames, armour stands) stay.
     */
    public static boolean dropsTemplateEntity(ServerLevel level, Entity entity) {
        if (!(entity instanceof Mob)) return false;
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        return UpsideDownBand.isInBandEntryLeadOrExit(level, entity.getBlockX(), entity.getBlockZ());
    }
}
