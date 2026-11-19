package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.sable.SableEntityCarry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A stopped train stays stopped until the player who has just walked out of its room is aboard.
 *
 * <h2>The problem it removes</h2>
 * <p>{@link PortalTrainFreeze} stops a train while everyone who could see it is in one of its rooms,
 * and lets it go the moment anybody is on its footprint — which a player swapping back onto a
 * carriage is, on the very tick of the swap. That is fine for the original exit: it stands in the
 * carriage's own chunk columns, so the client already has the train. It is not fine for an endless
 * room's exit copy hundreds of blocks out. The client there has neither the chunks nor the
 * carriage's Sable sub-level; until both arrive the player cannot move or be carried, and the train
 * that started up the same tick slides out from under them. Player log of 23 Oct 2026: out of a copy
 * 440 blocks from the train, landed on the exit carriage's corridor, and left standing in the
 * carriage behind it 0.7 s later.</p>
 *
 * <h2>The rule</h2>
 * <p>A player swapped onto a train that was frozen at that instant holds it frozen until Sable is
 * carrying them, they are gone, or {@link #HOLD_TICKS} pass. Nobody else was watching — that is why
 * it was frozen — so holding it a moment longer is as invisible as the freeze itself. For the
 * original exit the player is carried within a tick or two and the hold is over before it shows.</p>
 *
 * <p><b>Half of the fix.</b> A still train is not enough on its own: until the carriage reaches
 * the client the player falls straight through its deck and lands on the track bed under it, where
 * Sable never picks them up, so the hold only ever times out. The swap therefore also sends a
 * {@code SpawnDeckHoldPacket} that keeps the client at deck height until it is carried. Repro of
 * 24 Oct 2026, exit copy 383 blocks out: no holds, under the deck by tick 4 and left behind; train
 * hold alone, under the deck and released on timeout; both, carried on tick 3 and still aboard 8 s
 * later.</p>
 */
public final class PortalArrivalHold {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Longest a train waits for a player to be picked up, in ticks. The same scale as
     * {@code SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS}: enough for a client to receive the chunks and
     * the sub-level it lands on, short enough that a player who stepped straight off costs nothing.
     */
    static final long HOLD_TICKS = 100L;

    private record Hold(UUID trainId, String playerName, long since, long until) {}

    /** Player → the train they are waiting to be carried by. */
    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    private PortalArrivalHold() {}

    /** {@code player} has just swapped onto {@code trainId}, which was frozen when they did. */
    public static void noteArrival(ServerPlayer player, UUID trainId, long now) {
        if (trainId == null) return;
        HOLDS.put(player.getUUID(), new Hold(trainId, player.getName().getString(), now, now + HOLD_TICKS));
    }

    /**
     * The trains still waiting for somebody, after releasing every hold that is over. Called once
     * per server tick per level by {@link PortalTrainFreeze#tick}.
     */
    public static Set<UUID> heldTrains(ServerLevel level, List<ServerPlayer> players, long now) {
        if (HOLDS.isEmpty()) return Set.of();
        Map<UUID, ServerPlayer> byId = new HashMap<>(players.size());
        for (ServerPlayer player : players) byId.put(player.getUUID(), player);

        Set<UUID> held = new HashSet<>();
        Iterator<Map.Entry<UUID, Hold>> it = HOLDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Hold> entry = it.next();
            Hold hold = entry.getValue();
            ServerPlayer player = byId.get(entry.getKey());
            // Another level's player is not "gone" — their hold is that level's to judge. But a
            // player in none of this level's list who is also not on any level is; the level that
            // still has them answers for them, and the server-stop clear covers the rest.
            if (player == null && !onAnotherLevel(level, entry.getKey())) {
                release(it, hold, now, "gone");
                continue;
            }
            if (player == null) continue;
            boolean present = player.isAlive() && !player.isRemoved();
            boolean carried = present && SableEntityCarry.carrier(player) != null;
            if (keep(carried, present, now, hold.until())) {
                held.add(hold.trainId());
            } else {
                release(it, hold, now,
                    carried ? "carried" : present ? "timeout" : "gone");
            }
        }
        return held;
    }

    /**
     * Pure core: whether a hold survives this tick. Released by being carried (the point), by the
     * player no longer being there to carry, or by running out of time.
     */
    static boolean keep(boolean carried, boolean present, long now, long until) {
        return present && !carried && now < until;
    }

    /** Forget everything, for a server stop. */
    public static void clear() {
        HOLDS.clear();
    }

    private static boolean onAnotherLevel(ServerLevel level, UUID playerId) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        return player != null && player.level() != level;
    }

    private static void release(Iterator<Map.Entry<UUID, Hold>> it, Hold hold, long now,
                                String why) {
        it.remove();
        LOGGER.info("[DungeonTrain] Portal arrival hold released: player={} train={} after {} ticks ({})",
            hold.playerName(), hold.trainId(), now - hold.since(), why);
    }
}
