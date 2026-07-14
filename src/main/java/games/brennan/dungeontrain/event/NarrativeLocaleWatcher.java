package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.AinLocaleOverlay;
import games.brennan.dungeontrain.narrative.DeathLoreStore;
import games.brennan.dungeontrain.narrative.NarrativeContentLocale;
import games.brennan.dungeontrain.narrative.RandomBookRegistry;
import games.brennan.dungeontrain.narrative.StartingBookRegistry;
import games.brennan.dungeontrain.narrative.StoryRegistry;
import games.brennan.dungeontrain.narrative.WorldLanguage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Keeps the bundled narrative prose in the world <b>host</b>'s language.
 *
 * <p>Datapacks are world-global, so bundled prose can't key off each client's locale — it follows
 * the host, exactly like {@link WorldLanguage}-scoped relay content. This watcher resolves the host
 * locale, decides the supported {@link NarrativeContentLocale content locale}, and — only when it
 * actually changes — re-runs the four prose registries' {@code load(ResourceManager)} so the
 * host-language overlay is (re)applied. English hosts settle to {@code ""} and never reload.</p>
 *
 * <ul>
 *   <li>{@link PlayerEvent.PlayerLoggedInEvent} (HIGHEST) — re-evaluates the instant a player joins.
 *       The starting-book strike is deferred several ticks (and held through the intro cinematic —
 *       see {@code StartingBookEvents}), so the overlay is in place long before the first book is
 *       generated.</li>
 *   <li>{@link ServerTickEvent.Post} — a throttled re-check (~5&nbsp;s) that catches a host changing
 *       their Minecraft language mid-session without relogging, and a dedicated server's first
 *       player arriving/leaving.</li>
 *   <li>{@link ServerStoppedEvent} — resets to English so the next world in the same JVM starts from
 *       the base language until its own host resolves.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NarrativeLocaleWatcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Re-check cadence in server ticks (20 ticks = 1 s → ~5 s). */
    private static final int CHECK_PERIOD_TICKS = 100;

    private static int tickCounter = 0;

    private NarrativeLocaleWatcher() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        reevaluate(event.getEntity().getServer());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < CHECK_PERIOD_TICKS) return;
        tickCounter = 0;
        reevaluate(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        tickCounter = 0;
        NarrativeContentLocale.set("");
        AinLocaleOverlay.restoreBase();
    }

    /**
     * Resolve the host locale, normalise to a supported content locale (else English), and reload the
     * prose registries only when that differs from the currently-applied locale. Runs on the server
     * thread; safe to call frequently — it's a no-op once the locale has settled.
     */
    private static void reevaluate(MinecraftServer server) {
        if (server == null) return;
        ResourceManager rm = server.getResourceManager();
        String host = WorldLanguage.hostLocale(server); // "" when no host / unavailable
        String desired = NarrativeContentLocale.isSupported(rm, host) ? host : "";
        if (desired.equals(NarrativeContentLocale.current())) return;

        NarrativeContentLocale.set(desired);
        StoryRegistry.load(rm);
        RandomBookRegistry.load(rm);
        StartingBookRegistry.load(rm);
        DeathLoreStore.load(rm);
        // AIN item names / mob titles: overlaid via AIN's session-overlay API (host-keyed, English
        // players untouched), NOT a global datapack override. Guarded — a cross-mod API failure
        // must degrade to English names, never disrupt the server tick.
        try {
            AinLocaleOverlay.select(rm, desired);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] AIN item-name overlay failed for '{}' — leaving item names in AIN's base language",
                desired.isEmpty() ? "en (base)" : desired, t);
        }
        LOGGER.info("[DungeonTrain] Narrative content locale set to '{}' (host locale '{}') — prose registries reloaded",
            desired.isEmpty() ? "en (base)" : desired, host);
    }
}
