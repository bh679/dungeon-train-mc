package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player mirror of each client's personal carriage ceiling
 * ({@code ClientDisplayConfig.getMaxCarriages()} — the Max carriages row in Options → Dungeon Train).
 * The client is the authoritative store; it seeds this mirror on login, and again whenever the player
 * moves the slider while connected, via {@link games.brennan.dungeontrain.net.MaxCarriagesSyncPacket}.
 *
 * <p>Structurally this is {@link ContentModeMirror}, but nothing here is a permission, so there is no
 * fail-safe direction to get right: an unknown player reads as {@code 0} (no personal cap), which is
 * exactly the behaviour that shipped before this feature existed. A client on an older jar, or one
 * whose login sync hasn't landed yet, simply gets the render-distance window the server would have
 * given it anyway.</p>
 *
 * <p>This value can only ever <b>shorten</b> a player's window — the appender takes the smallest of
 * the render-distance target, the server's {@code numCarriages} maximum, and this. That is what makes
 * the packet safe to accept from any client unvalidated beyond a range clamp: the worst a hostile
 * client can do to itself is see less train, and it cannot affect anyone else's window.</p>
 *
 * <p>Cleared per-player on logout ({@link #forget}, from {@link PlayerJoinEvents}) and wholesale on
 * server stop, so a new world starts clean and every client re-seeds on its next login.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MaxCarriagesMirror {

    /** Per-player ceiling, seeded from the client sync. Absent = no personal cap (auto). */
    private static final Map<UUID, Integer> MAXES = new ConcurrentHashMap<>();

    private MaxCarriagesMirror() {}

    /**
     * Seed / update the mirror from the client's sync packet. Server thread.
     * {@code max} is clamped to {@code 0} ∪ {@code [4, 50]}; {@code 0} removes any personal cap.
     */
    public static void set(ServerPlayer player, int max) {
        if (player == null) return;
        if (max <= 0) {
            MAXES.remove(player.getUUID());
            return;
        }
        MAXES.put(player.getUUID(), Math.max(DungeonTrainConfig.MIN_CARRIAGES_EXPLICIT,
                                             Math.min(DungeonTrainConfig.MAX_CARRIAGES, max)));
    }

    /** {@code player}'s personal ceiling, or {@code 0} when they have none (the default). */
    public static int get(ServerPlayer player) {
        if (player == null) return 0;
        return MAXES.getOrDefault(player.getUUID(), 0);
    }

    /** Drop a player's mirrored ceiling when they leave; called from {@link PlayerJoinEvents} logout. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            MAXES.remove(playerId);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Nothing leaks into the next world: every client re-seeds its state on the next login.
        MAXES.clear();
    }
}
