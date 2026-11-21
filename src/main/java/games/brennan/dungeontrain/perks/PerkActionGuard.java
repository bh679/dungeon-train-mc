package games.brennan.dungeontrain.perks;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drops a second unlock or spend for the same perk inside {@link #WINDOW_MS} per player: one click
 * is one action, and a held key or a double-sent packet cannot double-spend.
 */
public final class PerkActionGuard {

    static final long WINDOW_MS = 100;

    private static final Map<UUID, Map<String, Long>> LAST = new ConcurrentHashMap<>();

    private PerkActionGuard() {}

    public static boolean allow(ServerPlayer player, String perkId) {
        return allow(player.getUUID(), perkId, System.currentTimeMillis());
    }

    static boolean allow(UUID player, String perkId, long now) {
        Map<String, Long> byPerk = LAST.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        Long last = byPerk.get(perkId);
        if (last != null && now - last < WINDOW_MS) return false;
        byPerk.put(perkId, now);
        return true;
    }

    public static void forget(UUID player) {
        LAST.remove(player);
    }
}
