package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtPlayerChangeGameModeCallback;
import games.brennan.dungeontrain.platform.event.DtPlayerLoginCallback;
import games.brennan.dungeontrain.platform.event.DtPlayerLogoutCallback;
import games.brennan.dungeontrain.platform.event.DtPlayerRespawnCallback;
import games.brennan.dungeontrain.platform.event.DtPriority;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for player-connection events (login,
 * logout, respawn, game-mode change). Auto-registered via {@link EventBusSubscriber}.
 * Exact semantic passthrough — no logic.
 *
 * <p><b>Priority:</b> login is the one category with a load-bearing tier split —
 * {@code CheatDetectionEvents} (HIGHEST) must run before {@code AchievementEvents}
 * (NORMAL). This bridge subscribes login at HIGHEST and NORMAL separately and
 * fires each tier's bucket, so the ordering — and interleaving with other mods'
 * login listeners — is identical to the former bus. Logout, respawn and game-mode
 * change were all single-tier NORMAL, so each is subscribed once and fires
 * {@code listeners()} (all buckets, which is only NORMAL here) in registration
 * order.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NeoForgeConnectionBridge {

    private NeoForgeConnectionBridge() {}

    // ---- PlayerLoggedInEvent (HIGHEST, NORMAL) ----------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerLoggedInHighest(PlayerEvent.PlayerLoggedInEvent event) {
        for (DtPlayerLoginCallback cb : DtEvents.PLAYER_LOGIN.listeners(DtPriority.HIGHEST)) {
            cb.onPlayerLoggedIn(event.getEntity());
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onPlayerLoggedInNormal(PlayerEvent.PlayerLoggedInEvent event) {
        for (DtPlayerLoginCallback cb : DtEvents.PLAYER_LOGIN.listeners(DtPriority.NORMAL)) {
            cb.onPlayerLoggedIn(event.getEntity());
        }
    }

    // ---- PlayerLoggedOutEvent (NORMAL) ------------------------------------

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        for (DtPlayerLogoutCallback cb : DtEvents.PLAYER_LOGOUT.listeners()) {
            cb.onPlayerLoggedOut(event.getEntity());
        }
    }

    // ---- PlayerRespawnEvent (NORMAL) --------------------------------------

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        for (DtPlayerRespawnCallback cb : DtEvents.PLAYER_RESPAWN.listeners()) {
            cb.onPlayerRespawn(event.getEntity(), event.isEndConquered());
        }
    }

    // ---- PlayerChangeGameModeEvent (NORMAL) -------------------------------

    @SubscribeEvent
    public static void onChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        for (DtPlayerChangeGameModeCallback cb : DtEvents.PLAYER_CHANGE_GAMEMODE.listeners()) {
            cb.onChangeGameMode(event.getEntity(), event.getNewGameMode());
        }
    }
}
