package games.brennan.dungeontrain.client.version;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tells players who are in a world, in chat, when a new Dungeon Train version is released.
 *
 * <p>Live only: the first successful poll of the JVM records a <em>baseline</em> — whatever was
 * already out when this session started — and only a release newer than that is announced.
 * Joining on an outdated jar says nothing here; the pause-menu version button covers that.</p>
 *
 * <p>Real releases only: comparison goes through {@link SemverCompare}, which looks at major.minor,
 * so the ~22 patch releases of the auto-release cascade never fire a notice.</p>
 *
 * <p>Client-side only — each player's client polls {@link UpdateFeed} itself, so it behaves the same
 * in singleplayer, on LAN and on a dedicated server, with no packet or server change.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveUpdateNotice {

    private static final long DEFAULT_INTERVAL_MS = 10 * 60 * 1000L;

    /** Gate 2 seam — shorten the poll interval, in seconds. */
    private static final String INTERVAL_PROPERTY = "dungeontrain.updatePollSeconds";

    private static final String RELEASE_URL = "https://github.com/bh679/dungeon-train-mc/releases/tag/v";

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private static long lastPollMs;
    /** Newest version known this session; {@code null} until the first successful poll. */
    private static String baseline;

    private LiveUpdateNotice() {}

    /**
     * Whether {@code latest} is a release worth announcing: newer (major.minor) than both what this
     * session already knew about and what is installed. A {@code null} baseline means the first
     * poll hasn't landed yet, so nothing counts as "new" yet.
     */
    static boolean shouldAnnounce(String installed, String baseline, String latest) {
        if (baseline == null || latest == null || latest.isEmpty()) return false;
        return SemverCompare.compare(latest, baseline) > 0
            && SemverCompare.compare(latest, installed) > 0;
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // A baseline is needed before anything can count as new — take it as soon as we're in.
        if (baseline == null) lastPollMs = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!enabled()) {
            baseline = null; // re-enabling starts a fresh baseline, never a backlog
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPollMs < intervalMs() || !IN_FLIGHT.compareAndSet(false, true)) return;
        lastPollMs = now;

        String installed = GitHubLatestReleaseFetcher.currentModVersion();
        UpdateFeed.fetchLatest(SharedConstants.getCurrentVersion().getName(), installed)
            .thenAcceptAsync(latest -> {
                IN_FLIGHT.set(false);
                onLatest(installed, latest);
            }, mc)
            .exceptionally(t -> {
                IN_FLIGHT.set(false);
                return null;
            });
    }

    private static void onLatest(String installed, String latest) {
        if (latest == null) return;
        if (baseline == null) {
            baseline = latest;
            return;
        }
        if (!shouldAnnounce(installed, baseline, latest)) return;
        baseline = latest;
        VersionCheckState.accept(VersionCheckState.Status.UPDATE_AVAILABLE, latest);
        announce(latest);
    }

    private static void announce(String version) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !ClientDisplayConfig.isUpdateNoticeChatEnabled()) return;
        MutableComponent link = Component.translatable("chat.dungeontrain.update_notice.link")
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, RELEASE_URL + version))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal(RELEASE_URL + version))));
        Component msg = Component.translatable("chat.dungeontrain.update_notice", version)
            .withStyle(ChatFormatting.GOLD)
            .append(" ")
            .append(link);
        player.displayClientMessage(msg, false);
    }

    /** Off when the player turned it off, and on dev builds unless a test feed is supplied. */
    private static boolean enabled() {
        if (!ClientDisplayConfig.isUpdateNoticeChatEnabled()) return false;
        return !DungeonTrain.isDevBuild() || System.getProperty(UpdateFeed.URL_PROPERTY) != null;
    }

    private static long intervalMs() {
        String s = System.getProperty(INTERVAL_PROPERTY);
        if (s == null) return DEFAULT_INTERVAL_MS;
        try {
            return Math.max(5, Long.parseLong(s.trim())) * 1000L;
        } catch (NumberFormatException e) {
            return DEFAULT_INTERVAL_MS;
        }
    }
}
