package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;

/**
 * The one definition of a difficulty <em>profile</em> key: each vanilla difficulty is its own
 * self-contained run, with its own Ender Chest ({@code EnderChestLockBridge}) and its own carried
 * loadout ({@link LoadoutStore}).
 *
 * <p>Dungeon Train persists two things across the death&rarr;new-world boundary: the Ender Chest (per
 * install, via EnderChestPersistence) and — when {@code keepInventory} is on — the player's inventory
 * and XP ({@link PendingInventory}). Without a partition, gear farmed on Peaceful walks straight into a
 * Hard run and the difficulty stops meaning anything. With one, switching difficulty is switching
 * profiles: your Hard stash is waiting where you left it, and so is your Peaceful one.</p>
 *
 * <p><b>Normal is the legacy key.</b> {@link #suffixFor} returns {@code ""} for Normal, so a Normal
 * profile keeps using the un-suffixed slot every existing stash is already stored under — nothing to
 * migrate, and no one loses a chest to this change. The other three difficulties start empty. This
 * matches the rule the echo pool uses for its untagged records (PlayerMob's
 * {@code ReincarnationDifficulty.LEGACY}), so "untagged means Normal" holds everywhere.</p>
 *
 * <p>Pure and vanilla-only: the key is {@link Difficulty#getSerializedName()}, which is why every repo
 * in the chain (PlayerMob, Discord Presence, the relay, DT) can derive the same key independently
 * without an integration seam.</p>
 */
public final class DifficultyPartition {

    /** The difficulty whose profile uses the un-suffixed (pre-partition) storage keys. */
    public static final Difficulty LEGACY = Difficulty.NORMAL;

    /** The legacy partition's key, i.e. the one untagged data belongs to. */
    public static final String LEGACY_KEY = LEGACY.getSerializedName();

    private DifficultyPartition() {}

    /** The partition key for {@code difficulty} — its vanilla serialized name. */
    public static String keyFor(Difficulty difficulty) {
        return (difficulty == null ? LEGACY : difficulty).getSerializedName();
    }

    /** The partition key for {@code level}'s current difficulty. */
    public static String keyFor(Level level) {
        return level == null ? LEGACY_KEY : keyFor(level.getDifficulty());
    }

    /**
     * The suffix to append to a storage key for this partition: {@code ""} for the legacy partition
     * (Normal), else {@code "|<key>"}. Kept out of the key itself so existing data stays reachable —
     * see the class notes.
     */
    public static String suffixFor(String partitionKey) {
        return partitionKey == null || partitionKey.isEmpty() || LEGACY_KEY.equals(partitionKey)
            ? ""
            : "|" + partitionKey;
    }

    /** {@link #suffixFor(String)} for a difficulty. */
    public static String suffixFor(Difficulty difficulty) {
        return suffixFor(keyFor(difficulty));
    }

    /** A storage key scoped to {@code partitionKey} — {@code base} unchanged on the legacy partition. */
    public static String scopeKey(String base, String partitionKey) {
        return base + suffixFor(partitionKey);
    }

    /**
     * The partition a player in {@code level} is actually stored under right now: {@link #keyFor(Level)}
     * when difficulty isolation is enabled, else {@link #LEGACY_KEY}.
     *
     * <p>The single answer to "which profile am I in", shared by every consumer (Ender Chest slot, loadout
     * locker, advancement sidecar). Each deriving its own would let them disagree — and a chest, a loadout
     * and an advancement file that disagree about the current profile is precisely how data gets written to
     * the wrong one.</p>
     */
    public static String activeKeyFor(Level level) {
        return isolationEnabled() ? keyFor(level) : LEGACY_KEY;
    }

    /** Whether difficulty profiles are enabled at all (config {@code difficultyIsolatedStash}). */
    public static boolean isolationEnabled() {
        return DungeonTrainConfig.getDifficultyIsolatedStash();
    }
}
