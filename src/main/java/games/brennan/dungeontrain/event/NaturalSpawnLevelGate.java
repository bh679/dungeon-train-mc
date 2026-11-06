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
 * <p>Runs on {@code PotentialSpawns}, before the spawner's weighted pick, because a mob refused any later still uses up
 * the attempt and thins out every other hostile. Only natural spawning reads this list: carriage contents,
 * {@code /summon} and spawn eggs are untouched.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NaturalSpawnLevelGate {

    /** Mods gated here. Kept in step with {@code GATED_NAMESPACES} in {@code scripts/difficulty/derive-mob-offsets.py}. */
    static final Set<String> GATED_NAMESPACES = Set.of("luminous_monsters");

    private NaturalSpawnLevelGate() {}

    @SubscribeEvent
    public static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<MobSpawnSettings.SpawnerData> locked = new ArrayList<>();
        int diffLevel = -1;
        for (MobSpawnSettings.SpawnerData data : event.getSpawnerDataList()) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(data.type).toString();
            if (!isGated(id)) continue;
            if (diffLevel < 0) diffLevel = DifficultyProgression.currentTier(level);
            if (isLocked(MobEffectOffsets.firstRoomLevel(id), diffLevel)) locked.add(data);
        }
        locked.forEach(event::removeSpawnerData);
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
