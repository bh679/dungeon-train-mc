package games.brennan.dungeontrain.difficulty;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.MinecraftServer;

/**
 * Single write path for the admin difficulty travelled-offset — the value behind
 * {@code /dungeontrain difficulty <tier>}, {@code /dungeontrain difficulty auto} and
 * {@code /dtp <x>}.
 *
 * <p>The offset is <em>read</em> from {@link DungeonTrainConfig#getDifficultyTravelledOffset()},
 * because its two consumers ({@link DifficultyProgression#effectiveTravelled} and
 * {@link DifficultyProgression#positionTier}) sit on static world-gen hot paths with no
 * {@code ServerLevel} in hand. But that config lives in the <strong>global</strong>
 * {@code config/dungeontrain-server.toml}, not per-world — so on its own it leaks an offset set
 * in one world into every other world, and survives a restart.</p>
 *
 * <p>So the authoritative copy is per-world, in {@link DungeonTrainWorldData}, and the config
 * value is a mirror of it. Every write goes through here to keep the two in step;
 * {@link DifficultyOffsetLifecycle} re-mirrors on world load and clears the offset when a new
 * run starts.</p>
 */
public final class DifficultyOffset {

    private DifficultyOffset() {}

    /**
     * Set the offset for this server's world: persisted into the overworld's
     * {@link DungeonTrainWorldData} and mirrored into the global server config that every
     * difficulty consumer reads.
     */
    public static void set(MinecraftServer server, int offset) {
        if (server == null) return;
        DungeonTrainWorldData.get(server.overworld()).setDifficultyTravelledOffset(offset);
        DungeonTrainConfig.setDifficultyTravelledOffset(offset);
    }

    /** Clear the offset — difficulty follows real travelled distance again. */
    public static void clear(MinecraftServer server) {
        set(server, DungeonTrainConfig.DEFAULT_DIFFICULTY_TRAVELLED_OFFSET);
    }
}
