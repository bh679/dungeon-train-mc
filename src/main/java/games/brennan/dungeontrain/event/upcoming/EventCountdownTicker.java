package games.brennan.dungeontrain.event.upcoming;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.ContentModeMirror;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tells every player online when an update-page event is 24 hours, 1 hour and 10 minutes away, and
 * when it starts ({@link EventCountdown}). Checks once a second; the event list refreshes itself
 * every 15 minutes ({@link UpcomingEventsClient}). Kid-mode players are left out (events are
 * external livestreams), and so is anyone with the Event reminders switch off.
 *
 * <p>What has been said is remembered for the server's life only, so a restart inside a stage's
 * short grace window may repeat that one line.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EventCountdownTicker {

    private static final int CHECK_EVERY_TICKS = 20;
    private static final Set<String> SENT = new HashSet<>();
    private static int ticks;

    private EventCountdownTicker() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++ticks % CHECK_EVERY_TICKS != 0) return;
        // Nobody to tell yet (a singleplayer world still loading): leave the stage unsaid, so the
        // first player in still hears it while its grace window lasts.
        if (event.getServer().getPlayerList().getPlayerCount() == 0) return;
        long nowMs = System.currentTimeMillis();
        UpcomingEventsClient.refreshIfStale(nowMs);
        List<EventCountdown.Due> due = EventCountdown.due(UpcomingEventsClient.events(), nowMs / 1000L, SENT);
        for (EventCountdown.Due d : due) {
            SENT.add(d.sentKey());
            broadcast(event.getServer(), d.event(), d.stage());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SENT.clear();
        ticks = 0;
        UpcomingEventsClient.reset();
    }

    /** Send one stage's line to everyone eligible; also used by the debug command. */
    public static void broadcast(MinecraftServer server, UpcomingEvent event, EventCountdown.Stage stage) {
        Component line = EventCountdownText.line(event, stage);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player, line, stage);
        }
    }

    static void send(ServerPlayer player, Component line, EventCountdown.Stage stage) {
        if (ContentModeMirror.isKid(player) || !ContentModeMirror.isEventRemindersOn(player.getUUID())) return;
        player.sendSystemMessage(line);
        if (stage == EventCountdown.Stage.TEN_MIN || stage == EventCountdown.Stage.NOW) {
            player.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.MASTER, 0.6f, 1.0f);
        }
    }
}
