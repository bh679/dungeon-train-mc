package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Whether the running server is a <b>multiplayer</b> one — the single definition shared by the
 * "Played Multiplayer" advancement ({@link AchievementEvents}) and the world-info telemetry the
 * relay classifies players from ({@code WorldInfoReporter}). Keeping one predicate is the point:
 * the advancement and the stat can never disagree about what "multiplayer" means.
 *
 * <p>A join is multiplayer when the server is dedicated (nobody owns it), when the joining player
 * is not the singleplayer owner (a guest on someone else's world), or when a second player is
 * connected (the LAN host's own world once a friend is on it).</p>
 *
 * <p><b>Sticky for the server run, never persisted.</b> Once any join is multiplayer, every join
 * for the rest of that run reports multiplayer — so the LAN <em>host</em> is credited too, not just
 * the guest who triggered it (opening a world to LAN does not re-login the host, exactly the reason
 * {@link AchievementEvents} awards every online player). The flag is cleared on
 * {@link ServerStoppedEvent}, so a later solo run of the same world reports single-player again:
 * a host who sometimes plays with friends and sometimes alone lands in "both" rather than being
 * permanently reclassified.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MultiplayerState {

    /** Set by the first multiplayer join of this server run; cleared on stop. */
    private static volatile boolean seenMultiplayer = false;

    private MultiplayerState() {}

    /**
     * The pure predicate — package-private so the three cases can be unit-tested without a running
     * server. A dedicated server has no singleplayer owner, so {@code singleplayerOwner} is always
     * false there; the explicit {@code dedicatedServer} term documents the intent rather than
     * relying on that.
     */
    static boolean multiplayerJoin(boolean dedicatedServer, boolean singleplayerOwner, int playerCount) {
        return dedicatedServer || !singleplayerOwner || playerCount >= 2;
    }

    /**
     * Record a player join and return whether this server run is multiplayer as of that join.
     * Idempotent and safe to call from every join path (both {@link AchievementEvents} and the
     * world-info report call it — whichever runs first sets the flag, the other sees it).
     */
    public static boolean observeJoin(MinecraftServer server, ServerPlayer joined) {
        if (server == null || joined == null) {
            return seenMultiplayer;
        }
        if (multiplayerJoin(server.isDedicatedServer(),
                server.isSingleplayerOwner(joined.getGameProfile()),
                server.getPlayerCount())) {
            markMultiplayer();
        }
        return seenMultiplayer;
    }

    /** Latch this server run as multiplayer. Package-private — {@link #observeJoin} is the real entry. */
    static void markMultiplayer() {
        seenMultiplayer = true;
    }

    /**
     * Whether this server run is multiplayer, without a join to observe. A dedicated server is
     * multiplayer on its own; otherwise this is the sticky flag set by {@link #observeJoin}.
     */
    public static boolean isMultiplayer(MinecraftServer server) {
        return (server != null && server.isDedicatedServer()) || seenMultiplayer;
    }

    /** Clear the per-run flag — the next server run starts single-player again. Also a test seam. */
    public static void reset() {
        seenMultiplayer = false;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        reset();
    }
}
