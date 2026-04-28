package games.brennan.dungeontrain.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Identifies which dimension a Dungeon Train world starts the player in. The
 * value is chosen at world creation via the World Type dropdown
 * ({@code dungeontrain:dungeon_train} → OVERWORLD, {@code dungeon_train_nether}
 * → NETHER, {@code dungeon_train_end} → END), persisted on
 * {@link DungeonTrainWorldData}, and consulted by {@code TrainBootstrapEvents}
 * (where to spawn the starter train) and {@code PlayerJoinEvents} (where to
 * teleport joining players). Worldgen for all three dimensions stays vanilla
 * apart from the existing overworld bedrock-floor modification.
 */
public enum StartingDimension {
    OVERWORLD("overworld", Level.OVERWORLD),
    NETHER("the_nether", Level.NETHER),
    END("the_end", Level.END);

    private final String nbtId;
    private final ResourceKey<Level> levelKey;

    StartingDimension(String nbtId, ResourceKey<Level> levelKey) {
        this.nbtId = nbtId;
        this.levelKey = levelKey;
    }

    public String nbtId() {
        return nbtId;
    }

    public ResourceKey<Level> levelKey() {
        return levelKey;
    }

    public static StartingDimension fromNbt(String s) {
        if (s == null) return OVERWORLD;
        for (StartingDimension d : values()) {
            if (d.nbtId.equals(s)) return d;
        }
        return OVERWORLD;
    }

    /**
     * Map a world-preset path (e.g. {@code "dungeon_train_nether"}) to the
     * starting dimension it implies. Unknown paths — including the plain
     * {@code "dungeon_train"} preset, its Y-variants, and any non-DT preset —
     * resolve to {@link #OVERWORLD}, since those all generate the player into
     * the overworld.
     */
    public static StartingDimension fromPresetPath(String path) {
        if ("dungeon_train_nether".equals(path)) return NETHER;
        if ("dungeon_train_end".equals(path)) return END;
        return OVERWORLD;
    }
}
