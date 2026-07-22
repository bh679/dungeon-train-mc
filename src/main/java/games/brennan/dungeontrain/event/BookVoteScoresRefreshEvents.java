package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.BookVoteScores;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Periodically refreshes the {@link BookVoteScores} snapshot from the relay's anonymous
 * {@code /books/vote-summary} aggregate, so the local random/starting book rolls always weight by
 * reasonably fresh community sentiment — without any per-roll network I/O (the rolls only read the
 * cached snapshot). {@link SharedBookRefreshEvents} template: first refresh shortly after server
 * start, then every ~5 minutes (vote aggregates move slowly; no need for the shared pool's 30 s
 * cadence). Gated on the same operator master as shared-book discovery
 * ({@link SharedBookGate#canDiscover}) — the one switch that means "this world reads community book
 * data from the relay"; when off, no fetches, factor stays 1, rolls behave exactly as today.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BookVoteScoresRefreshEvents {

    /** Refresh cadence in server ticks (20 ticks = 1 s → ~5 min). */
    static final int REFRESH_PERIOD_TICKS = 6000;

    /** Short delay after server start before the first refresh, so the relay base url is settled. */
    static final int FIRST_REFRESH_DELAY_TICKS = 100;

    private static int tickCounter = 0;
    /** Counts down after start; a first refresh fires when it reaches zero. -1 = no pending first refresh. */
    private static int firstRefreshCountdown = -1;

    private BookVoteScoresRefreshEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        tickCounter = 0;
        firstRefreshCountdown = FIRST_REFRESH_DELAY_TICKS;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SharedBookGate.canDiscover()) return;

        if (firstRefreshCountdown > 0) {
            firstRefreshCountdown--;
            if (firstRefreshCountdown == 0) {
                firstRefreshCountdown = -1;
                BookVoteScores.refreshAsync();
                return;
            }
        }

        tickCounter++;
        if (tickCounter >= REFRESH_PERIOD_TICKS) {
            tickCounter = 0;
            BookVoteScores.refreshAsync();
        }
    }
}
