package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player mirror of each client's Discord Presence network-access consent (the
 * one-time "use the internet?" prompt — {@code DiscordPresenceClientConfig.isGranted()}). The client
 * is the authoritative store; it seeds this mirror on login via
 * {@link games.brennan.dungeontrain.net.NetworkConsentSyncPacket}.
 *
 * <p>Used by {@link SharedBookGate#canContribute} to decide whether a signing player may upload their
 * book to the relay. <b>Fail-closed:</b> {@link #isGranted(ServerPlayer)} returns {@code false} when
 * no sync has arrived (a client without Dungeon Train's network-consent packet, or before its login
 * sync lands), so a missing signal never leaks a contribution to the relay.</p>
 *
 * <p>Mirrors the structure of {@link DevMessageConsent}'s per-player store: a concurrent map (the sync
 * packet handler hops to the server thread, but concurrent is cheap insurance), cleared per-player on
 * logout ({@link #forget}, called from {@link PlayerJoinEvents}) and wholesale on server stop (so a
 * new world starts clean and each client re-seeds on its next login).</p>
 */
public final class NetworkConsentMirror {

    /** Per-player granted flag, seeded from the client login sync. Absent = not-yet-known = denied. */
    private static final Map<UUID, Boolean> GRANTED = new ConcurrentHashMap<>();

    private NetworkConsentMirror() {}

    /** Seed / update the mirror from the client's sync packet. Server thread. */
    public static void set(ServerPlayer player, boolean granted) {
        if (player == null) return;
        GRANTED.put(player.getUUID(), granted);
    }

    /**
     * True only when {@code player}'s client has affirmatively synced network consent as granted.
     * Fail-closed: returns {@code false} for an unknown player or a {@code null} argument.
     */
    public static boolean isGranted(ServerPlayer player) {
        if (player == null) return false;
        return GRANTED.getOrDefault(player.getUUID(), false);
    }

    /** Drop a player's mirrored consent when they leave; called from {@link PlayerJoinEvents} logout. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            GRANTED.remove(playerId);
        }
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        // Nothing leaks into the next world: every client re-seeds its state on the next login.
        GRANTED.clear();
    }
}
