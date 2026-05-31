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

    /**
     * Roll the starting dimension for a respawn — either when the player
     * clicks "New World" / "Same World" on the death screen (consumed by
     * {@code DeathScreenLayoutHandler.launchWorld}) or when they click the
     * vanilla "Respawn" button (consumed by {@code RespawnDimensionEvents}).
     * Caller passes a uniform draw on {@code [0, 1)}: {@code r < 0.01} → End
     * (1%), {@code r < 0.06} → Nether (5%), else Overworld (94%). The world's
     * current starting dimension is ignored — every death rolls independently,
     * so a player already in the Nether still has a 94% chance of waking up in
     * the Overworld.
     *
     * <p>Pure function over {@code r} so unit tests can pin boundaries
     * exactly without stubbing a {@code RandomSource}. Lives on this enum
     * (not on a client-only class) so server code can call it too.</p>
     */
    public static StartingDimension rollRespawnDimension(double r) {
        if (r < 0.01) return END;
        if (r < 0.06) return NETHER;
        return OVERWORLD;
    }
}
