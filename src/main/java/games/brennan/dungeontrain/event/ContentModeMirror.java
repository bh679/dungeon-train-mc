package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ContentMode;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player mirror of each client's {@link ContentMode} (the Adult / Kid choice made on
 * the first-launch consent card — {@code ClientDisplayConfig.getContentMode()}). The client is the
 * authoritative store; it seeds this mirror on login, and again whenever the setting changes while
 * connected, via {@link games.brennan.dungeontrain.net.ContentModeSyncPacket}.
 *
 * <p>Structurally this is {@link NetworkConsentMirror}, with one deliberate difference: <b>the
 * fail-safe direction is inverted.</b> The consent mirror fails closed on <i>sending</i> — an unknown
 * player is treated as not-consenting, so nothing of theirs leaks out. This one fails closed on
 * <i>receiving</i>: an unknown player is treated as {@link ContentMode#KID}, so a client that never
 * sent a sync — one running an older Dungeon Train, or one whose login sync hasn't landed yet — is
 * never served adult content on the assumption that silence means consent.</p>
 *
 * <p>That inversion has a visible cost worth knowing about: a player on an older jar gets kid-safe
 * content until they update. That is the correct way round for this feature; the alternative failure
 * is a child seeing a stranger's book because a packet was late.</p>
 *
 * <p>Cleared per-player on logout ({@link #forget}, from {@link PlayerJoinEvents}) and wholesale on
 * server stop, so a new world starts clean and every client re-seeds on its next login.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ContentModeMirror {

    /** Per-player mode, seeded from the client sync. Absent = not-yet-known = KID (see class javadoc). */
    private static final Map<UUID, ContentMode> MODES = new ConcurrentHashMap<>();

    private ContentModeMirror() {}

    /** Seed / update the mirror from the client's sync packet. Server thread. */
    public static void set(ServerPlayer player, ContentMode mode) {
        if (player == null || mode == null) return;
        MODES.put(player.getUUID(), mode);
    }

    /**
     * {@code player}'s content mode. Fail-safe: an unknown player, or a {@code null} argument, reads as
     * {@link ContentMode#KID} — the restrictive answer — so a missing sync withholds content rather
     * than leaking it.
     */
    public static ContentMode get(ServerPlayer player) {
        if (player == null) return ContentMode.KID;
        return MODES.getOrDefault(player.getUUID(), ContentMode.KID);
    }

    /** True when {@code player} is in Kid mode (including the unknown-player fail-safe). */
    public static boolean isKid(ServerPlayer player) {
        return get(player).isKid();
    }

    /** Drop a player's mirrored mode when they leave; called from {@link PlayerJoinEvents} logout. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            MODES.remove(playerId);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Nothing leaks into the next world: every client re-seeds its state on the next login.
        MODES.clear();
    }
}
