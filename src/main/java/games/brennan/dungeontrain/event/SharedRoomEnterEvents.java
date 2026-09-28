package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Credits;
import games.brennan.dungeontrain.train.SharedCarriageMessage;
import games.brennan.dungeontrain.train.SharedRoomRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends the gray chat hint the moment a player walks into a drifting dimensional carriage — the
 * room twin of {@link SharedCarriageEnterEvents}, with the same beats, burst limit and grace.
 *
 * <p>Where a carriage is found under a player's feet, a room is found around them:
 * {@link PortalCarriageEvents#portalRoomBodyPairKey} says which pair's room body holds the player,
 * corridors excluded, and the registry says whether that room drifts. Keyed per pair rather than per
 * sub-level, and otherwise the same: fire once on the transition in, hold the credit and death lines
 * back a beat each, and never more than a few announcements in ten seconds.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedRoomEnterEvents {

    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final int MESSAGE_BURST = 3;
    private static final long MESSAGE_WINDOW_MS = 10_000L;
    private static final int CREDIT_DELAY_TICKS = 50;
    private static final int DEATH_DELAY_TICKS = 45;
    /** A room is left by walking out through a corridor, so one poll off it is a real exit. */
    private static final int OFF_GRACE_POLLS = 2;

    private static final Map<UUID, Integer> LAST_PAIR = new ConcurrentHashMap<>();
    private static final Map<UUID, long[]> RECENT_MSG_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> OFF_POLLS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<SharedCarriageEnterEvents.PendingLine>> PENDING = new ConcurrentHashMap<>();

    private SharedRoomEnterEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player p = event.getEntity();
        if (p.level().isClientSide() || !(p instanceof ServerPlayer player)) return;
        if (!SharedCarriageGate.canDiscover()) return;
        releaseDueLines(player);
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        UUID id = player.getUUID();

        SharedRoomRegistry.Instance inst = null;
        if (!SharedRoomRegistry.isEmpty()) {
            Integer pairKey = PortalCarriageEvents.portalRoomBodyPairKey(
                    DungeonTrainWorldData.get(level).dims(), player.getX(), player.getY(), player.getZ());
            if (pairKey != null) inst = SharedRoomRegistry.byPair(pairKey);
        }
        if (inst == null) {
            int off = OFF_POLLS.merge(id, 1, (a, b) -> Math.min(a + b, OFF_GRACE_POLLS));
            if (off >= OFF_GRACE_POLLS) LAST_PAIR.remove(id);
            return;
        }
        OFF_POLLS.remove(id);
        Integer last = LAST_PAIR.put(id, inst.pairKey);
        if (last != null && last == inst.pairKey) return; // still in the same room

        if (!admit(id, System.currentTimeMillis())) {
            PENDING.remove(id);
            return;
        }
        boolean own = inst.authoredHere;
        player.sendSystemMessage(own
                ? SharedCarriageMessage.ownRoom(level.getRandom())
                : inst.leasedFromPool
                        ? SharedCarriageMessage.seenRoom(level.getRandom())
                        : SharedCarriageMessage.newRoom(level.getRandom()));
        Credits credits = inst.credits;
        Credits shown = own ? new Credits("", credits.editors(), credits.editorCount()) : credits;
        List<SharedCarriageEnterEvents.PendingLine> queue = new ArrayList<>(2);
        int due = player.tickCount + CREDIT_DELAY_TICKS;
        String locale = WorldInfoReporter.clientLanguage(player);
        Component credit = SharedCarriageMessage.creditLine(locale, shown, level.getRandom());
        if (credit != null) queue.add(new SharedCarriageEnterEvents.PendingLine(credit, due));
        Component died = SharedCarriageMessage.deathLine(locale, inst.deaths(), level.getRandom());
        if (died != null) queue.add(new SharedCarriageEnterEvents.PendingLine(died, due + DEATH_DELAY_TICKS));
        if (queue.isEmpty()) PENDING.remove(id);
        else PENDING.put(id, List.copyOf(queue));
    }

    private static boolean admit(UUID id, long now) {
        long[] stamps = RECENT_MSG_MS.get(id);
        if (stamps == null) {
            RECENT_MSG_MS.put(id, new long[] {now});
            return true;
        }
        if (stamps.length >= MESSAGE_BURST && now - stamps[0] < MESSAGE_WINDOW_MS) return false;
        int keep = Math.min(stamps.length, MESSAGE_BURST - 1);
        long[] next = new long[keep + 1];
        System.arraycopy(stamps, stamps.length - keep, next, 0, keep);
        next[keep] = now;
        RECENT_MSG_MS.put(id, next);
        return true;
    }

    private static void releaseDueLines(ServerPlayer player) {
        UUID id = player.getUUID();
        List<SharedCarriageEnterEvents.PendingLine> pending = PENDING.get(id);
        if (pending == null || pending.isEmpty()) return;
        int sent = SharedCarriageEnterEvents.dueCount(pending, player.tickCount);
        if (sent == 0) return;
        for (int i = 0; i < sent; i++) player.sendSystemMessage(pending.get(i).line());
        if (sent >= pending.size()) PENDING.remove(id);
        else PENDING.put(id, List.copyOf(pending.subList(sent, pending.size())));
    }

    /** Test/reset seam. */
    static void clear() {
        LAST_PAIR.clear();
        RECENT_MSG_MS.clear();
        OFF_POLLS.clear();
        PENDING.clear();
    }
}
