package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Carries a player's perk unlock levels across a Dungeon Train "next world" transition. Unlocks live
 * on the player in the world's save, and Board anew starts a fresh save, so without this every unlock
 * (including one bought on the death screen moments before) would be gone in the next run while the
 * Advancement Points that paid for it came back.
 *
 * <p>Same lifetime as {@link PendingInventory}: captured on the old server thread during
 * {@code launchWorld}, consumed once on the new world's first login, then cleared. Never populated on
 * a dedicated server, where a respawn keeps the attachment anyway. Only unlock levels travel; points
 * are lost on death by design. The carry never crosses difficulty profiles, because Advancement Points
 * are earned per profile and a carried unlock would spend points that profile never earned.</p>
 *
 * <p>Common-safe: imports no client classes.</p>
 */
public final class PendingPerks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile UUID playerId;
    private static volatile Map<String, Integer> unlocks;
    private static volatile String partition;

    private PendingPerks() {}

    /** Snapshot {@code player}'s unlock levels. Server thread only. */
    public static void capture(ServerPlayer player) {
        set(player.getUUID(), Perks.state(player).unlocks(), DifficultyPartition.keyFor(player.level()));
        LOGGER.info("[DungeonTrain] perk carry: captured {} unlocked perk(s) for {} ({})",
            unlocks.size(), player.getName().getString(), partition);
    }

    static void set(UUID id, Map<String, Integer> levels, String partitionKey) {
        playerId = id;
        unlocks = Map.copyOf(levels);
        partition = partitionKey;
    }

    public static boolean isPresentFor(UUID id) {
        return unlocks != null && id != null && id.equals(playerId);
    }

    /** Whether the carry was captured on {@code partitionKey}'s difficulty profile. */
    static boolean matches(String partitionKey) {
        String captured = partition;
        return captured != null && captured.equals(partitionKey);
    }

    /** The carried levels, or empty when nothing is held. */
    static Map<String, Integer> carried() {
        Map<String, Integer> u = unlocks;
        return u == null ? Map.of() : u;
    }

    /**
     * The unlock levels after the carry lands: for each perk, the higher of what the player already holds
     * and what was carried. Never lowers a level.
     */
    public static Map<String, Integer> merged(Map<String, Integer> current, Map<String, Integer> carried) {
        Map<String, Integer> out = new LinkedHashMap<>(current);
        carried.forEach((id, level) -> {
            if (level != null && level > out.getOrDefault(id, 0)) out.put(id, level);
        });
        return Map.copyOf(out);
    }

    /**
     * Take the carry for {@code player} when it was captured on their current difficulty profile: the
     * levels to apply, or empty (and a log line) on a mismatch. Clears the holder either way.
     */
    public static Map<String, Integer> take(ServerPlayer player) {
        String here = DifficultyPartition.keyFor(player.level());
        Map<String, Integer> levels = matches(here) ? carried() : Map.of();
        if (!matches(here)) {
            LOGGER.info("[DungeonTrain] perk carry: captured on {}, now on {}; dropped", partition, here);
        }
        clear();
        return levels;
    }

    public static void clear() {
        playerId = null;
        unlocks = null;
        partition = null;
    }
}
