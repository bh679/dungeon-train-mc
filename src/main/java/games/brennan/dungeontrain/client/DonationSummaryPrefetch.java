package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fills {@link DonationSummaryCache} the first time the player reaches the title screen, so the
 * menu's splash cycle can quote live update counts (see {@code SplashUpdateCountsMixin}).
 *
 * <p>Before this, only the death screen ever fetched the ledger — which meant the menu had nothing
 * to say until the player had died at least once. The request is the same anonymous, edge-cacheable
 * {@code GET /donations/summary} the death screen makes, with no player name attached: it carries
 * no identity, so it needs no consent gate, exactly like {@code OfficialLinksFetcher}.</p>
 *
 * <p><b>One shot per launch.</b> The figures move slowly (a day bucket cannot change faster than a
 * day) and the death screen refreshes them on every death anyway, so re-fetching on every visit to
 * the menu would be traffic for nothing.</p>
 *
 * <p>The very first menu of a launch usually loses the race with its own request — the splash is
 * chosen when the screen is constructed, and the reply lands a moment later. That visit shows an
 * ordinary quote and every visit after it can show a figure; a splash is not worth blocking a menu
 * for.</p>
 */
@EventBusSubscriber(modid = "dungeontrain", value = Dist.CLIENT)
public final class DonationSummaryPrefetch {

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private DonationSummaryPrefetch() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;
        if (!STARTED.compareAndSet(false, true)) return;
        DonationSummaryClient.fetch(null, summary ->
                Minecraft.getInstance().execute(() -> DonationSummaryCache.set(summary)));
    }
}
