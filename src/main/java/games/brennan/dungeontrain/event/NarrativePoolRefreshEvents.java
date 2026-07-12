package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.NarrativePool;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.narrative.WorldLanguage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Periodically refreshes the {@link NarrativePool} snapshot from the relay for the narrative-lectern
 * DISCOVERY half, so lecterns always have a warm, reasonably fresh pool of approved player narratives to
 * draw from — without any per-interaction network I/O (the lectern resolve only reads the cached snapshot).
 *
 * <p>The parallel of {@link SharedBookRefreshEvents}, but gated on {@link SharedBookGate#canDiscoverNarratives()}
 * and — because a player narrative is read across many lecterns — each refresh PINS the world's in-progress
 * seriesIds via {@code include=} so a mid-read series stays resolvable even after it rotates past the random
 * window. Pins are the most-recently-started {@link #MAX_PINNED} series (continuity-first selection keeps the
 * simultaneously-incomplete count tiny, so a small cap covers every series a world could be mid-read on).</p>
 *
 * <p>When discovery is disabled the ticker does nothing (no fetches, no cost). The tick counter is a plain
 * server-thread int — {@link ServerTickEvent.Post} is single-threaded.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NarrativePoolRefreshEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Refresh cadence in server ticks (20 ticks = 1 s → ~30 s). */
    static final int REFRESH_PERIOD_TICKS = 600;

    /** Short delay after server start before the first refresh, so relay-base-url + world are settled. */
    static final int FIRST_REFRESH_DELAY_TICKS = 100;

    /** Cap on the in-progress seriesIds pinned into a fetch (most recently started kept). */
    static final int MAX_PINNED = 24;

    private static int tickCounter = 0;
    /** Counts down after start; a first refresh fires when it reaches zero. -1 = no pending first refresh. */
    private static int firstRefreshCountdown = -1;

    private NarrativePoolRefreshEvents() {}

        public static void onServerStarted(net.minecraft.server.MinecraftServer server) {
        tickCounter = 0;
        firstRefreshCountdown = FIRST_REFRESH_DELAY_TICKS;
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        NarrativePool.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SharedBookGate.canDiscoverNarratives()) return;

        if (firstRefreshCountdown > 0) {
            firstRefreshCountdown--;
            if (firstRefreshCountdown == 0) {
                firstRefreshCountdown = -1;
                refresh(event.getServer());
                return;
            }
        }

        tickCounter++;
        if (tickCounter >= REFRESH_PERIOD_TICKS) {
            tickCounter = 0;
            refresh(event.getServer());
        }
    }

    /** Gather the world's in-progress seriesIds to pin and fire an off-thread pool refresh. */
    private static void refresh(MinecraftServer server) {
        if (server == null) return;
        Set<String> pinned = Set.of();
        try {
            ServerLevel overworld = server.overworld();
            if (overworld != null) {
                pinned = mostRecent(NarrativeProgressData.get(overworld).startedPlayerSeriesIds(), MAX_PINNED);
            }
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] narrative pool pin gathering failed: {}", t.toString());
        }
        NarrativePool.refreshAsync(pinned, WorldLanguage.hostLocale(server));
    }

    /** The last (most recently started) {@code max} ids from {@code ids}, order preserved. */
    private static Set<String> mostRecent(List<String> ids, int max) {
        if (ids.isEmpty()) return Set.of();
        int from = Math.max(0, ids.size() - max);
        return new LinkedHashSet<>(ids.subList(from, ids.size()));
    }
}
