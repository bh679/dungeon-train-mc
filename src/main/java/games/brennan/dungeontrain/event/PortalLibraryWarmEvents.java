package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.WorldLanguage;
import games.brennan.dungeontrain.portal.PortalRoomAuthorLocks;
import games.brennan.dungeontrain.portal.PortalRoomBooks;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Asks the relay who has written what, ahead of any portal room needing to know.
 *
 * <h2>Why ahead of time</h2>
 * <p>A room that stocks its shelves from a community author cannot be built until that answer has
 * arrived, and it arrives seconds into a world at the earliest. Fetching on demand meant the first
 * library a player reached was a hall of bare shelves — so the directory is warmed at world load
 * instead, and until it lands those rooms are simply not drawn (see {@code PortalLibraryReadiness}).
 * By the time one can be chosen, it can be filled.</p>
 *
 * <p>Modelled on {@link SharedBookRefreshEvents}, which warms the ordinary community-book pool the
 * same way and for the same reason: a short delay after start so the relay base URL and world are
 * settled, then a throttled re-check. Both are gated on {@link SharedBookGate#canDiscover()}, so a
 * world with discovery off costs nothing.</p>
 *
 * <h2>Only the pages that are actually wanted</h2>
 * <p>Rooms ask for authors within their own book range, so the pages to warm are the distinct ranges
 * across the rooms that stock one — two, for the shipped content. A room weighted away from
 * signatures never needs the signature page, and that is decided per room rather than fetched
 * wholesale.</p>
 *
 * <p>The re-check keeps running while any wanted page is still missing, so a relay that comes back
 * mid-session is picked up without a restart. Once every page has an answer it goes quiet: the warm
 * costs one map lookup per page per period thereafter.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalLibraryWarmEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Re-check cadence in server ticks (20 ticks = 1 s → ~15 s). */
    static final int WARM_PERIOD_TICKS = 300;

    /** Short delay after server start before the first warm, so relay-base-url + world are settled. */
    static final int FIRST_WARM_DELAY_TICKS = 100;

    private static int tickCounter = 0;
    /** Counts down after start; the first warm fires when it reaches zero. -1 = none pending. */
    private static int firstWarmCountdown = -1;
    /** Logged once per world, so a session says out loud that libraries are waiting on the relay. */
    private static boolean loggedWaiting = false;

    private PortalLibraryWarmEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        tickCounter = 0;
        firstWarmCountdown = FIRST_WARM_DELAY_TICKS;
        loggedWaiting = false;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SharedBookGate.canDiscover()) return;

        if (firstWarmCountdown > 0) {
            firstWarmCountdown--;
            if (firstWarmCountdown == 0) {
                firstWarmCountdown = -1;
                warm(event.getServer());
            }
            return;
        }

        tickCounter++;
        if (tickCounter >= WARM_PERIOD_TICKS) {
            tickCounter = 0;
            warm(event.getServer());
        }
    }

    /**
     * Ask for every directory page the world's library rooms will want and does not yet have.
     *
     * <p>Needs a player only for the host locale and a random source — no page fetched here is
     * player-specific, so an empty server simply waits until somebody joins.</p>
     */
    private static void warm(MinecraftServer server) {
        if (server == null) return;
        List<PortalRoomBooks> wanted = wantedSettings();
        if (wanted.isEmpty()) return;             // no room in this world stocks an author

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        ServerPlayer any = players.get(0);
        boolean kidSafe = WorldLanguage.hostFetchesKidSafeBooks(server);

        boolean allReady = true;
        for (PortalRoomBooks books : wanted) {
            if (!PortalRoomAuthorLocks.canFill(books)) allReady = false;
            PortalRoomAuthorLocks.warm(any, books, kidSafe);
        }
        if (!allReady && !loggedWaiting) {
            loggedWaiting = true;
            LOGGER.info("[DungeonTrain] Portal library rooms are waiting on the relay for their "
                + "authors — they will not be drawn until it answers.");
        }
    }

    /**
     * The distinct author ranges this world's portal rooms ask for.
     *
     * <p>Distinct by range rather than by room: two rooms wanting authors with 10–24 books are one
     * question. Group members are included — a sub-variant carries its own Books setting and is the
     * thing actually stamped.</p>
     */
    static List<PortalRoomBooks> wantedSettings() {
        List<PortalRoomBooks> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : TrackVariantRegistry.namesFor(TrackKind.PORTAL_ROOM)) {
            PortalRoomBooks books = PortalRoomSettings.of(name).books();
            if (!books.locks()) continue;
            // The weights decide which pages are wanted, and the range decides what is on them — so
            // two rooms with the same range but different weights are still one question per page.
            String key = books.minBooks() + ":" + books.maxBooks() + ":"
                + PortalRoomAuthorLocks.directoryKindsFor(books);
            if (seen.add(key)) out.add(books);
        }
        return out;
    }
}
