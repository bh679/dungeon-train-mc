package games.brennan.dungeontrain.client.version;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.util.PresenceLine;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.time.Duration;
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

    private static final Logger LOGGER = LogUtils.getLogger();

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
            LOGGER.info("Update notice: baseline {} (installed {})", latest, installed);
            return;
        }
        if (!shouldAnnounce(installed, baseline, latest)) return;
        baseline = latest;
        LOGGER.info("Update notice: announcing {} (installed {})", latest, installed);
        VersionCheckState.accept(VersionCheckState.Status.UPDATE_AVAILABLE, latest);
        // Look up its name and when it was published, then work out "N ago" at the moment the
        // line is sent. Either lookup failing only drops its part of the notice.
        ReleaseName.fetch(latest, installed)
            .thenCombine(ReleaseTime.fetchPublishedAt(latest, installed), Announcement::new)
            .thenAcceptAsync(a -> announce(latest, a.info(), a.publishedAtMs()), Minecraft.getInstance());
    }

    private record Announcement(ReleaseName.Info info, Long publishedAtMs) {}

    /**
     * An ordinary release gets one line. A notable one gets its name in bold, its summary and a
     * chime; a major one a banner, its summary, a toast and a fanfare, so it can't be missed.
     */
    private static void announce(String version, ReleaseName.Info info, Long publishedAtMs) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !ClientDisplayConfig.isUpdateNoticeChatEnabled()) return;
        // Empty parent so the "ago" clause doesn't inherit the link's underline.
        MutableComponent footer = Component.empty().append(link(version));
        if (publishedAtMs != null) {
            String locale = mc.getLanguageManager().getSelected();
            Duration ago = ReleaseTime.since(publishedAtMs, System.currentTimeMillis());
            footer.append(Component.translatable("chat.dungeontrain.update_notice.ago",
                    PresenceLine.agoComponent(locale, ago)).withStyle(ChatFormatting.GRAY));
        }
        ReleaseName.Tier tier = info == null ? ReleaseName.Tier.NORMAL : info.tier();
        LOGGER.info("Update notice: {} release {} ({})", tier, version, info == null ? "unnamed" : info.name());
        switch (tier) {
            case MAJOR -> {
                player.displayClientMessage(Component.empty(), false);
                player.displayClientMessage(Component.translatable("chat.dungeontrain.update_notice.major_header")
                    .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), false);
                player.displayClientMessage(INDENT.copy().append(Component.translatable(
                        "chat.dungeontrain.update_notice.major_name", name(info, ChatFormatting.GOLD), version)
                    .withStyle(ChatFormatting.GOLD)), false);
                summary(player, info, ChatFormatting.WHITE);
                player.displayClientMessage(INDENT.copy().append(footer), false);
                SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("toast.dungeontrain.update_notice.major"), Component.literal(info.name()));
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F));
            }
            case NOTABLE -> {
                player.displayClientMessage(Component.translatable("chat.dungeontrain.update_notice.notable",
                        name(info, ChatFormatting.AQUA), version).withStyle(ChatFormatting.GOLD), false);
                summary(player, info, ChatFormatting.GRAY);
                player.displayClientMessage(INDENT.copy().append(footer), false);
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1.0F));
            }
            case NORMAL -> {
                MutableComponent headline = info == null
                    ? Component.translatable("chat.dungeontrain.update_notice", version)
                    : Component.translatable("chat.dungeontrain.update_notice.named", Component.literal(info.name()), version);
                player.displayClientMessage(headline.withStyle(ChatFormatting.GOLD).append(" ").append(footer), false);
            }
        }
    }

    private static final Component INDENT = Component.literal("  ");

    private static MutableComponent link(String version) {
        return Component.translatable("chat.dungeontrain.update_notice.link")
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, RELEASE_URL + version))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal(RELEASE_URL + version))));
    }

    private static Component name(ReleaseName.Info info, ChatFormatting colour) {
        return Component.literal(info.name()).withStyle(colour, ChatFormatting.BOLD);
    }

    private static void summary(LocalPlayer player, ReleaseName.Info info, ChatFormatting colour) {
        if (info.summary().isEmpty()) return;
        player.displayClientMessage(INDENT.copy().append(Component.literal(info.summary()).withStyle(colour)), false);
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
