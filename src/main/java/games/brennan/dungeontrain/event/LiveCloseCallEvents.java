package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.AllAtOnceAdvancement;
import games.brennan.dungeontrain.advancement.CloseCallPace;
import games.brennan.dungeontrain.advancement.CloseCallRules;
import games.brennan.dungeontrain.advancement.CloseCallRules.Found;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.LiveCloseCallPacket;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Channel;
import games.brennan.dungeontrain.player.LiveStreamers;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.player.ServerLiveStatus;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live Eye close calls: a player estimated a few minutes from a rare single-life advancement
 * ({@link CloseCallRules}) is reported to the relay, and the streamer on air sees
 * "X is about N minutes from a rare challenge [Hand them the Live Eye]". Handing it over is the
 * ordinary Live Eye gift: the player must accept ({@code LiveGiftClient}, {@link LiveGiftServer}).
 *
 * <p>Fed by {@code AchievementEvents#notifyRunDistance} on every boarded scan; {@link CloseCallPace}
 * keeps one reading a minute. Nothing is sent while the player is AFK, is the one streaming, could
 * not take a Live Eye anyway, or nobody else is on air. Each advancement alerts at most twice a life,
 * at 20 and at 5 minutes ({@link CloseCallRules.Stage}, recorded in
 * {@link ModDataAttachments#CLOSE_CALLS_SENT_THIS_LIFE}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveCloseCallEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<UUID, CloseCallPace> PACE = new ConcurrentHashMap<>();

    private LiveCloseCallEvents() {}

    /** A boarded scan booked distance. Server thread. */
    public static void onProgress(ServerPlayer player, double runMeters) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUUID();
        CloseCallPace pace = PACE.getOrDefault(uuid, CloseCallPace.EMPTY);
        if (!pace.due(now)) return;
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        CloseCallPace.Sample reading = new CloseCallPace.Sample(now, AchievementEvents.effectiveTravelled(run), runMeters);
        CloseCallPace next = pace.with(reading);
        PACE.put(uuid, next);
        if (mayAlert(player)) evaluate(player, next, reading);
    }

    private static boolean mayAlert(ServerPlayer player) {
        MinecraftServer server = player.server;
        if (PlayerActivityTracker.isAfk(player)) return false;
        if (player.getUUID().equals(LiveStreamers.get(server))) return false;
        if (!ServerLiveStatus.isSomeoneElseLive(server, player)) return false;
        return LiveGiftServer.refusalFor(player) == null;
    }

    private static void evaluate(ServerPlayer player, CloseCallPace pace, CloseCallPace.Sample reading) {
        List<String> sent = player.getData(ModDataAttachments.CLOSE_CALLS_SENT_THIS_LIFE.get());
        Found found = CloseCallRules.find(pace, reading, t -> eligible(player, t), new HashSet<>(sent));
        if (!found.any()) return;
        List<String> marked = new ArrayList<>(sent);
        marked.addAll(found.keys());
        player.setData(ModDataAttachments.CLOSE_CALLS_SENT_THIS_LIFE.get(), List.copyOf(marked));
        int minutes = CloseCallRules.minutes(found.etaMs());
        String name = player.getGameProfile().getName();
        LOGGER.info("[DungeonTrain] Close call ({}): {} is ~{} min from {}; telling the stream",
            found.stage(), name, minutes, found.close().get(0).id());
        MinecraftServer server = player.server;
        UUID uuid = player.getUUID();
        LiveFeedClient.closeCall(LiveFeedClient.viewerBase(Channel.LIVE), uuid, name, minutes)
            .whenComplete((r, t) -> server.execute(() -> {
                if (t != null || r == null || !r.ok()) {
                    LOGGER.debug("[DungeonTrain] Close call for {} not delivered (status {})", name, r == null ? 0 : r.status());
                    return;
                }
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p != null && notified(r)) DungeonTrainNet.sendTo(p, new LiveCloseCallPacket(LiveCloseCallPacket.WATCH_SECONDS));
            }));
    }

    private static boolean notified(LiveFeedClient.Result r) {
        return r.body() != null && r.body().has("notified") && r.body().get("notified").getAsBoolean();
    }

    /** Still on course for {@code t} this life: not yet earned, and The Challenge's rules all held. */
    private static boolean eligible(ServerPlayer player, CloseCallRules.Target t) {
        AdvancementHolder holder = player.server.getAdvancements().get(t.id());
        if (holder == null || player.getAdvancements().getOrStartProgress(holder).isDone()) return false;
        return t != CloseCallRules.THE_CHALLENGE || !AllAtOnceAdvancement.anyRuleBroken(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!event.isEndConquered()) PACE.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PACE.remove(event.getEntity().getUUID());
    }
}
