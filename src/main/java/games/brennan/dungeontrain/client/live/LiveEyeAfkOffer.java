package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.AfkAutoPause;
import games.brennan.dungeontrain.client.ClientActivityReporter;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * A Live Eye holder who has walked away offers the eye to everyone watching, as if they had typed
 * {@code /offereye all} ({@link LiveGiftClient#offerAll}): the first viewer to accept gets it, and the
 * relay hands the channel over once their own stream claims it.
 *
 * <p><b>Away</b> is {@link AfkAutoPause}'s idle clock: no key, click, scroll, camera turn or cursor
 * movement for {@link AfkAutoPause#IDLE_PAUSE_MS} (2 min), or 5 min with a screen open, since reading a
 * book or sorting a chest is not being away. The pause menu counts as the world, so the offer goes out
 * the moment the 2-minute auto-pause opens it. Dead or spectating resets that clock, so a death (which
 * the relay already offers on) never doubles up here.</p>
 *
 * <p><b>Once, then every {@link #REOFFER_MS} while still away.</b> The repeat reaches viewers who tuned
 * in during the absence; the relay skips anyone whose offer is still open. Only the first successful
 * offer of an absence tells the holder; the repeats and the "nobody watching" / "already offered"
 * replies stay quiet. Any input starts a new absence. An auto-offer is not a gift the holder chose, so
 * it does not send {@code gifted_live_eye}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveEyeAfkOffer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** While still away, offer again this often (2 min) for viewers who arrived since. */
    public static final long REOFFER_MS = 120_000L;

    /** Check once a second; the clocks here are measured in minutes. */
    private static final int CHECK_EVERY_TICKS = 20;

    static final String KEY = "chat.dungeontrain.live_gift.afk_offered";

    /** Idle time at which this absence last offered; {@code -1} = not yet. Client thread only. */
    private static long offeredAtIdleMs = -1L;
    /** Has this absence told the holder yet? */
    private static boolean announced = false;
    private static int tickCounter = 0;

    private LiveEyeAfkOffer() {}

    // ------------------------------------------------------------ pure rules

    /** How long away before the offer: the auto-pause's wait, the pause menu counting as the world. Pure. */
    static long thresholdMs(boolean screenOpen, boolean onGameMenu) {
        return AfkAutoPause.thresholdMs(screenOpen && !onGameMenu);
    }

    /**
     * Pure decision: on air, away past {@link #thresholdMs}, and either not offered yet this absence
     * ({@code offeredAtIdleMs < 0}) or {@link #REOFFER_MS} since the last offer.
     */
    static boolean shouldOffer(boolean streaming, long idleMs, boolean screenOpen, boolean onGameMenu,
                               long offeredAtIdleMs) {
        if (!streaming || idleMs < thresholdMs(screenOpen, onGameMenu)) return false;
        return offeredAtIdleMs < 0L || idleMs - offeredAtIdleMs >= REOFFER_MS;
    }

    /** Did input start a new absence since the last offer? The idle clock only ever runs backwards on input. Pure. */
    static boolean newAbsence(long idleMs, long offeredAtIdleMs) {
        return offeredAtIdleMs >= 0L && idleMs < offeredAtIdleMs;
    }

    // ------------------------------------------------------------------- tick

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (++tickCounter < CHECK_EVERY_TICKS) return;
        tickCounter = 0;
        Minecraft mc = Minecraft.getInstance();
        String token = LiveStreamController.get().streamToken();
        if (mc.player == null || mc.level == null || token == null) {
            reset();
            return;
        }
        long idleMs = AfkAutoPause.idleMs();
        if (newAbsence(idleMs, offeredAtIdleMs)) reset();
        if (!shouldOffer(true, idleMs, mc.screen != null, ClientActivityReporter.onGameMenu(), offeredAtIdleMs)) return;

        offeredAtIdleMs = idleMs;
        LOGGER.info("[DungeonTrain] Live Eye holder away {} s: offering the eye to all viewers", idleMs / 1000L);
        LiveFeedClient.giftOfferAll(DungeonTrain.relayBaseUrl(), token).thenAcceptAsync(r -> {
            if (r.ok()) {
                if (!announced && offeredAtIdleMs >= 0L) {
                    announced = true;
                    LiveGiftClient.say(mc, Component.translatable(KEY).withStyle(ChatFormatting.GRAY));
                }
                return;
            }
            String error = r.str("error");
            boolean quiet = (r.status() == 404 && "not_watching".equals(error))
                || (r.status() == 409 && ("already_offered".equals(error) || "already_given".equals(error)));
            if (!quiet) {
                LOGGER.warn("[DungeonTrain] Live Eye away-offer refused: HTTP {} {}", r.status(), error,
                    r.error());
            }
        }, mc);
    }

    private static void reset() {
        offeredAtIdleMs = -1L;
        announced = false;
    }
}
