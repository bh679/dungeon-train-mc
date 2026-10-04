package games.brennan.dungeontrain.client.version;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.builder.BuilderWorldCheck;
import games.brennan.dungeontrain.client.version.compare.FullSemver;
import games.brennan.dungeontrain.client.version.compare.InstalledVersion;
import games.brennan.dungeontrain.client.version.compare.NewestRelease;
import games.brennan.dungeontrain.client.version.compare.Platform;
import games.brennan.dungeontrain.client.version.compare.UpdatePage;
import games.brennan.dungeontrain.client.version.compare.VersionCompareState;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.narrative.PluralRules;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Tells a player who joins a game on an already-outdated build, once per game, how many releases
 * behind they are, with a link to the update page:
 * {@code [Dungeon Train] You're 23 releases behind (v0.1137.0 → v0.1160.0). [⬆ Update to v0.1160.0]}.
 *
 * <p>Fills the gap {@link LiveUpdateNotice} leaves — that one only speaks when a release lands
 * mid-session. The count is {@link NewestRelease}'s, the same number the title-screen card and the
 * death screen show (real MAJOR.MINOR releases across both launchers; cascade PATCH ticks never
 * count), built from the launcher listings {@link VersionCompareState} fetches.</p>
 *
 * <p>"Once per game" is remembered in {@link OutdatedNoticeSeen}, keyed by {@link #gameKey}. Reuses
 * the existing "update notice in chat" toggle. Dev builds stay quiet unless
 * {@code -PmockInstalledVersion} pretends to be an older build (the Gate 2 seam).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class OutdatedVersionNotice {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Wait after joining so the line isn't buried under the join messages. */
    static final int DELAY_TICKS = 100;
    /** Stop waiting for slow launcher listings after this long; an unknown answer says nothing. */
    static final int GIVE_UP_TICKS = 20 * 60;

    private static final String PREFIX_KEY = "chat.dungeontrain.bug_response.prefix";
    private static final String LINE_KEY = "gui.dungeontrain.bug_response.outdated.";
    private static final String LINK_KEY = "gui.dungeontrain.death.update_button";

    @Nullable private static String pendingKey;
    private static int ticksSinceJoin;
    @Nullable private static OutdatedNoticeSeen seen;

    private OutdatedVersionNotice() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        pendingKey = null;
        if (DungeonTrain.isDevBuild() && !InstalledVersion.isMocked()) return;
        if (BuilderWorldCheck.isBuilderWorld()) return;
        String key = gameKey(Minecraft.getInstance());
        if (key == null || seen().contains(key)) return;
        pendingKey = key;
        ticksSinceJoin = 0;
        VersionCompareState.ensureFetched();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        pendingKey = null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (pendingKey == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (++ticksSinceJoin < DELAY_TICKS) return;
        if (anyLoading() && ticksSinceJoin < GIVE_UP_TICKS) return;

        String key = pendingKey;
        pendingKey = null;
        if (!ClientDisplayConfig.isUpdateNoticeChatEnabled()) return;
        Optional<FullSemver> installed = InstalledVersion.get();
        if (installed.isEmpty()) return;
        Platform launcher = Platform.current();
        Optional<NewestRelease.Target> target = NewestRelease.across(installed.get(), launcher,
                VersionCompareState.versions(launcher), VersionCompareState.versions(launcher.other()));
        if (target.isEmpty() || target.get().releasesBehind() <= 0) return;

        LOGGER.info("[DungeonTrain] Outdated notice: {} is {} release(s) behind {} — telling the player once for {}",
                installed.get(), target.get().releasesBehind(), target.get().version(), key);
        mc.player.displayClientMessage(message(ClientLanguage.selected(), target.get().releasesBehind(),
                installed.get(), target.get().version(), UpdatePage.url(installed, launcher)), false);
        seen().add(key);
    }

    /**
     * The chat line: gold {@code [Dungeon Train]}, the behind-line with its count in yellow, then an
     * aqua underlined link to {@code url}. Pure, so it is unit-tested.
     */
    static Component message(@Nullable String locale, int behind, FullSemver installed, FullSemver latest, String url) {
        Component count = Component.literal(Integer.toString(behind)).withStyle(ChatFormatting.YELLOW);
        Component line = Component.translatable(LINE_KEY + PluralRules.category(locale, behind),
                count, installed.toString(), latest.toString()).withStyle(ChatFormatting.WHITE);
        Component link = Component.literal("[")
                .append(Component.translatable(LINK_KEY, latest.toString()))
                .append("]")
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
        return Component.translatable(PREFIX_KEY).withStyle(ChatFormatting.GOLD)
                .append(" ").append(line).append(" ").append(link);
    }

    /**
     * Which game this is. Singleplayer: save folder plus seed — a deleted world's folder name gets
     * reused by the next one, the seed tells them apart. Multiplayer: the server address, the
     * closest a client can get to "this game". Null when neither can be told.
     */
    @Nullable
    static String gameKey(Minecraft mc) {
        try {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null) {
                return singleplayerKey(server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString(),
                        server.getWorldData().worldGenOptions().seed());
            }
            ServerData data = mc.getCurrentServer();
            return data == null ? null : multiplayerKey(data.ip);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Outdated notice: couldn't tell which game this is; staying quiet: {}", e.toString());
            return null;
        }
    }

    static String singleplayerKey(String levelId, long seed) {
        return "sp:" + levelId + ":" + seed;
    }

    static String multiplayerKey(String address) {
        return "mp:" + address;
    }

    private static boolean anyLoading() {
        for (Platform p : Platform.values()) {
            if (VersionCompareState.status(p) == VersionCompareState.Status.LOADING) return true;
        }
        return false;
    }

    private static OutdatedNoticeSeen seen() {
        if (seen == null) seen = new OutdatedNoticeSeen(PlayerDataPaths.root().resolve(OutdatedNoticeSeen.FILE_NAME));
        return seen;
    }
}
