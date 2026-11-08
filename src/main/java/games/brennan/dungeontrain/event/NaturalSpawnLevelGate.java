package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.difficulty.MobEffectOffsets;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Keeps a mob pack's natural spawns behind the train: each mob joins the spawner's candidates only once the
 * current Diff-Level reaches the lowest level any carriage room can place it
 * ({@link MobEffectOffsets#firstRoomLevel}). A mob in no room never spawns naturally. The level is
 * {@link DifficultyProgression#currentTier}, so it drops with the furthest player's death, like mob gear and buffs.
 *
 * <p>Creeper Overhaul's creepers also take over from the vanilla creeper, one biome at a time: the vanilla creeper
 * leaves a spawn list once any of that biome's own overhaul creepers is unlocked. DT empties the mod's
 * {@code remove_vanilla.json}, which would otherwise strip it from those biomes from the start. The cave creeper does
 * not count, because the mod lists it in every biome it gives a creeper, so it would replace vanilla everywhere at once.</p>
 *
 * <p>Runs on {@code PotentialSpawns}, before the spawner's weighted pick, because a mob refused any later still uses up
 * the attempt and thins out every other hostile. Only natural spawning reads this list: carriage contents,
 * {@code /summon} and spawn eggs are untouched.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NaturalSpawnLevelGate {

    static final String CREEPER_OVERHAUL = "creeperoverhaul";

    /** Mods gated here. Kept in step with {@code GATED_NAMESPACES} in {@code scripts/difficulty/derive-mob-offsets.py}. */
    static final Set<String> GATED_NAMESPACES = Set.of("luminous_monsters", CREEPER_OVERHAUL);

    static final String VANILLA_CREEPER = "minecraft:creeper";
    /** Listed in every biome the mod gives a creeper, so it never stands in for a biome's own. */
    static final String CAVE_CREEPER = CREEPER_OVERHAUL + ":cave_creeper";

    private NaturalSpawnLevelGate() {}

    @SubscribeEvent
    public static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<MobSpawnSettings.SpawnerData> removed = new ArrayList<>();
        List<MobSpawnSettings.SpawnerData> vanillaCreepers = new ArrayList<>();
        boolean biomeCreeperUnlocked = false;
        int diffLevel = -1;
        for (MobSpawnSettings.SpawnerData data : event.getSpawnerDataList()) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(data.type).toString();
            if (VANILLA_CREEPER.equals(id)) vanillaCreepers.add(data);
            if (!isGated(id)) continue;
            if (diffLevel < 0) diffLevel = DifficultyProgression.currentTier(level);
            if (isLocked(MobEffectOffsets.firstRoomLevel(id), diffLevel)) removed.add(data);
            else if (replacesVanillaCreeper(id)) biomeCreeperUnlocked = true;
        }
        if (biomeCreeperUnlocked) removed.addAll(vanillaCreepers);
        removed.forEach(event::removeSpawnerData);
    }

    /** A biome's own overhaul creeper, which takes the vanilla creeper's place there once it is unlocked. */
    static boolean replacesVanillaCreeper(String entityId) {
        return entityId.startsWith(CREEPER_OVERHAUL + ":") && !CAVE_CREEPER.equals(entityId);
    }

    static boolean isGated(String entityId) {
        int colon = entityId.indexOf(':');
        return colon > 0 && GATED_NAMESPACES.contains(entityId.substring(0, colon));
    }

    /** Locked below its first room level, and always when no room places it. */
    static boolean isLocked(OptionalInt firstRoomLevel, int diffLevel) {
        return firstRoomLevel.isEmpty() || diffLevel < firstRoomLevel.getAsInt();
    }
}
