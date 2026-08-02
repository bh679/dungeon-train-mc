package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Credits;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.SharedCarriageMessage;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3d;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends a gray chat hint the moment a player steps onto a shared carriage — a fresh-canvas line
 * for a locally-placed carriage, or a "someone was here" line for one leased from the relay pool
 * (distinguished by {@link SharedCarriageRegistry.Instance#leasedFromPool}). See
 * {@link SharedCarriageMessage}.
 *
 * <p>Detection polls each player's footing on a coarse cadence ({@link #CHECK_INTERVAL_TICKS}) and fires
 * only on a TRANSITION onto a new shared carriage (keyed by sub-level + carriage index), with a short
 * per-player cooldown so hopping a group boundary can't spam. Server-side only.</p>
 *
 * <p>A leased carriage adds a second line crediting its contributors, held back
 * {@link #CREDIT_DELAY_TICKS} so it reads as an afterthought rather than arriving in the same instant
 * as the flavour line. That beat is why the pending queue is drained every tick while the footing poll
 * stays coarse.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedCarriageEnterEvents {

    private static final int CHECK_INTERVAL_TICKS = 10;   // ~0.5 s
    private static final long MESSAGE_COOLDOWN_MS = 3000L;
    /** Beat between the flavour line and the credit line, so they land as two thoughts, not one block. */
    private static final int CREDIT_DELAY_TICKS = 50;     // 2.5 s
    /**
     * Consecutive polls a player must be off every shared carriage before we forget which one they were
     * on. Footing needs a block at the feet, so a JUMP reads as "left the carriage" for a poll or two —
     * without this grace, landing again counted as a fresh entry and re-sent the whole message.
     */
    private static final int OFF_GRACE_POLLS = 4;         // ~2 s

    /** Per-player key ("subLevelId:pIdx") of the shared carriage they were last found standing on. */
    private static final Map<UUID, String> LAST_KEY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_MSG_MS = new ConcurrentHashMap<>();
    /** Consecutive polls each player has been off every shared carriage (saturates at the grace). */
    private static final Map<UUID, Integer> OFF_POLLS = new ConcurrentHashMap<>();
    /** Credit lines waiting out {@link #CREDIT_DELAY_TICKS} before they are sent. */
    private static final Map<UUID, PendingCredit> PENDING_CREDIT = new ConcurrentHashMap<>();

    /** A credit line held back until {@code dueTick} (the player's own tickCount). */
    private record PendingCredit(Component line, int dueTick) {}

    private SharedCarriageEnterEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player p = event.getEntity();
        if (p.level().isClientSide() || !(p instanceof ServerPlayer player)) return;
        if (!SharedCarriageGate.canDiscover()) return;
        // Before the coarse footing poll: the credit beat needs tick resolution, and the poll only
        // runs every CHECK_INTERVAL_TICKS.
        releaseDueCredit(player);
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        String key = null;
        boolean leased = false;
        Credits credits = Credits.EMPTY;
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        if (!carriages.isEmpty()) {
            Trains.Carriage c = CarriageDeck.carriageUnder(carriages, player);
            if (c != null) {
                ManagedShip ship = c.ship();
                Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
                ship.worldToShip(local);
                SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.resolve(
                        ship.subLevelId(),
                        (int) Math.floor(local.x), (int) Math.floor(local.y), (int) Math.floor(local.z));
                if (inst != null) {
                    key = ship.subLevelId() + ":" + inst.pIdx;
                    leased = inst.leasedFromPool;
                    credits = inst.credits;
                }
            }
        }

        UUID id = player.getUUID();
        if (key == null) {
            // Only forget the carriage after a SUSTAINED absence — a jump inside one carriage drops
            // footing for a poll or two, and treating that as leaving re-sent the message on landing.
            // Walking to a different carriage is unaffected: its key differs, so that still messages
            // immediately whatever this counter says.
            int off = OFF_POLLS.merge(id, 1, (a, b) -> Math.min(a + b, OFF_GRACE_POLLS));
            if (off >= OFF_GRACE_POLLS) LAST_KEY.remove(id); // genuinely left → a later re-entry messages again
            return;
        }
        OFF_POLLS.remove(id);
        if (key.equals(LAST_KEY.get(id))) return; // still on the same carriage
        LAST_KEY.put(id, key);

        long now = System.currentTimeMillis();
        Long last = LAST_MSG_MS.get(id);
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) return;
        LAST_MSG_MS.put(id, now);

        player.sendSystemMessage(leased
                ? SharedCarriageMessage.seenCarriage(level.getRandom())
                : SharedCarriageMessage.newCarriage(level.getRandom()));
        // Second line naming who built it, held back a beat so it doesn't land in the same instant as the
        // flavour line. Null whenever there is nobody to credit — a fresh local carriage, a relay too old
        // to send names, or a build stored before names were captured — so those carriages keep exactly
        // the single-line message they showed before, with no dangling pause after it.
        Component credit = SharedCarriageMessage.creditLine(credits, level.getRandom());
        if (credit != null) {
            PENDING_CREDIT.put(id, new PendingCredit(credit, player.tickCount + CREDIT_DELAY_TICKS));
        } else {
            PENDING_CREDIT.remove(id); // a previous carriage's credit must not trail into this one
        }
    }

    /** Send a player's held-back credit line once its beat has elapsed. */
    private static void releaseDueCredit(ServerPlayer player) {
        PendingCredit pending = PENDING_CREDIT.get(player.getUUID());
        if (pending == null || player.tickCount < pending.dueTick()) return;
        PENDING_CREDIT.remove(player.getUUID());
        player.sendSystemMessage(pending.line());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_KEY.remove(id);
        LAST_MSG_MS.remove(id);
        OFF_POLLS.remove(id);
        // Drop any undelivered credit line — tickCount resets on rejoin, and a Dungeon Train death starts
        // a whole new world, so a line held here would otherwise surface in a run it has nothing to do with.
        PENDING_CREDIT.remove(id);
    }
}
