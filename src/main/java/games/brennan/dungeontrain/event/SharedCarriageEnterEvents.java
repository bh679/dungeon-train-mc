package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Credits;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Deaths;
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

import java.util.ArrayList;
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
 * only on a TRANSITION onto a new shared carriage (keyed by sub-level + carriage index), rate-limited to
 * {@link #MESSAGE_BURST} announcements per {@link #MESSAGE_WINDOW_MS} per player so walking a train of
 * shared carriages can't fill chat. Server-side only.</p>
 *
 * <p>A leased carriage adds a second line crediting its contributors, held back
 * {@link #CREDIT_DELAY_TICKS} so it reads as an afterthought rather than arriving in the same instant
 * as the flavour line — and, if anyone has died aboard, a third line naming them a further
 * {@link #DEATH_DELAY_TICKS} after that. Those beats are why the pending queue is drained every tick
 * while the footing poll stays coarse. Both later lines are optional and independently so: a carriage
 * with nobody to credit and nobody to mourn sends the flavour line alone, exactly as it always has.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedCarriageEnterEvents {

    private static final int CHECK_INTERVAL_TICKS = 10;   // ~0.5 s
    /**
     * Sliding-window rate limit on the entry announcement: at most {@link #MESSAGE_BURST} of them in any
     * {@link #MESSAGE_WINDOW_MS}, per player. A flavour line plus its credit line count as ONE, since
     * they announce a single entry.
     */
    private static final int MESSAGE_BURST = 3;
    private static final long MESSAGE_WINDOW_MS = 10_000L;
    /** Beat between the flavour line and the credit line, so they land as two thoughts, not one block. */
    private static final int CREDIT_DELAY_TICKS = 50;     // 2.5 s
    /**
     * Beat between the credit line and the death line. Measured from the CREDIT beat, not from entry, so
     * the death line keeps its own pause in front of it whether or not a credit line preceded it — on a
     * carriage with nobody to credit it simply arrives that much sooner after the flavour line.
     */
    private static final int DEATH_DELAY_TICKS = 45;      // ~2.25 s
    /**
     * Consecutive polls a player must be off every shared carriage before we forget which one they were
     * on. Footing needs a block at the feet, so a JUMP reads as "left the carriage" for a poll or two —
     * without this grace, landing again counted as a fresh entry and re-sent the whole message.
     */
    private static final int OFF_GRACE_POLLS = 4;         // ~2 s

    /** Per-player key ("subLevelId:pIdx") of the shared carriage they were last found standing on. */
    private static final Map<UUID, String> LAST_KEY = new ConcurrentHashMap<>();
    /** Send times of each player's last {@link #MESSAGE_BURST} announcements, oldest first. */
    private static final Map<UUID, long[]> RECENT_MSG_MS = new ConcurrentHashMap<>();
    /** Consecutive polls each player has been off every shared carriage (saturates at the grace). */
    private static final Map<UUID, Integer> OFF_POLLS = new ConcurrentHashMap<>();
    /**
     * Lines waiting out their beat before being sent, in due order, per player. A list rather than one
     * slot because a leased carriage can have two of them (credit, then deaths) on separate beats; it is
     * only ever replaced wholesale, never appended to across carriages, so a line can't outlive the entry
     * that queued it.
     */
    private static final Map<UUID, List<PendingLine>> PENDING = new ConcurrentHashMap<>();

    /** A line held back until {@code dueTick} (the player's own tickCount). */
    record PendingLine(Component line, int dueTick) {}

    /**
     * How many of {@code pending} have come due at {@code tick} — the leading run of them, stopping at
     * the first that has not. The queue is built in due order, so a later line can never jump ahead of
     * one still waiting out its beat. Pure, and package-private only so it can be tested without a
     * server player to hang ticks off.
     */
    static int dueCount(List<PendingLine> pending, int tick) {
        if (pending == null) return 0;
        int n = 0;
        for (PendingLine p : pending) {
            if (tick < p.dueTick()) break;
            n++;
        }
        return n;
    }

    private SharedCarriageEnterEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player p = event.getEntity();
        if (p.level().isClientSide() || !(p instanceof ServerPlayer player)) return;
        if (!SharedCarriageGate.canDiscover()) return;
        // Before the coarse footing poll: the held beats need tick resolution, and the poll only
        // runs every CHECK_INTERVAL_TICKS.
        releaseDueLines(player);
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        String key = null;
        boolean leased = false;
        boolean own = false;
        // Whether the relay credits THIS player (not merely someone in this world) with building it.
        boolean ownByThisPlayer = false;
        Credits credits = Credits.EMPTY;
        Deaths deaths = Deaths.EMPTY;
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
                    own = inst.authoredHere;
                    ownByThisPlayer = inst.isAuthoredBy(player.getUUID());
                    credits = inst.credits;
                    deaths = inst.deaths();
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

        // Triggered before the rate limit: a milestone the player earned shouldn't hinge on how chatty the
        // last ten seconds were.
        //
        // "You built this" wins over "someone's been here" — it's the more specific fact, and it's the
        // one the player can verify by looking around.
        // "The rails brought your own build back" — the milestone the own-build lease exists to create.
        // Gated on the AUTHOR uuid, not on `own`: on a multiplayer world `authoredHere` is true when the
        // build belongs to ANY player present, and handing the achievement to a bystander standing in
        // someone else's carriage would be plainly wrong.
        if (ownByThisPlayer) {
            games.brennan.dungeontrain.advancement.ModAdvancementTriggers.GAMEPLAY_ACTION.get()
                    .trigger(player, "drift_own_return");
        }

        if (!admit(id, System.currentTimeMillis())) {
            // Nothing announces this carriage, so an earlier carriage's held lines must not arrive now and
            // appear to describe it.
            PENDING.remove(id);
            return;
        }

        player.sendSystemMessage(own
                ? SharedCarriageMessage.ownCarriage(level.getRandom())
                : leased
                        ? SharedCarriageMessage.seenCarriage(level.getRandom())
                        : SharedCarriageMessage.newCarriage(level.getRandom()));
        // Second line naming who built it, held back a beat so it doesn't land in the same instant as the
        // flavour line. Null whenever there is nobody to credit — a fresh local carriage, a relay too old
        // to send names, or a build stored before names were captured — so those carriages keep exactly
        // the single-line message they showed before, with no dangling pause after it.
        //
        // On the player's OWN build the creator clause is dropped: the line above already said it is
        // theirs, so naming them again reads as a bug. What is genuinely news there is who has changed it
        // while it was away — which is exactly the credit's stand-alone editor phrasing.
        Credits shown = own ? new Credits("", credits.editors(), credits.editorCount()) : credits;
        List<PendingLine> queue = new ArrayList<>(2);
        int due = player.tickCount + CREDIT_DELAY_TICKS;
        String locale = WorldInfoReporter.clientLanguage(player);
        Component credit = SharedCarriageMessage.creditLine(locale, shown, level.getRandom());
        if (credit != null) queue.add(new PendingLine(credit, due));
        // Third line: who died aboard. Independent of the credit line — a carriage can have deaths and no
        // named builder, or the reverse — but it always sits a beat BEHIND wherever the credit beat fell,
        // so the two never land together when both are present.
        Component died = SharedCarriageMessage.deathLine(locale, deaths, level.getRandom());
        if (died != null) queue.add(new PendingLine(died, due + DEATH_DELAY_TICKS));
        // Replaced, never appended: whatever the last carriage was still holding has been overtaken and
        // must not trail into this one.
        if (queue.isEmpty()) PENDING.remove(id);
        else PENDING.put(id, List.copyOf(queue));
    }

    /**
     * Whether this player may be sent an announcement now, recording it when so. The window is full while
     * the OLDEST of the retained send times is still inside it. Timestamps are replaced with a fresh array
     * rather than shifted in place, so a concurrent reader never sees a half-shifted window.
     */
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

    /**
     * Send whichever of a player's held-back lines have come due, in order, and keep the rest. The queue
     * is built in due order, so the first entry not yet due ends the drain — nothing can jump ahead of a
     * line still waiting.
     */
    private static void releaseDueLines(ServerPlayer player) {
        UUID id = player.getUUID();
        List<PendingLine> pending = PENDING.get(id);
        if (pending == null || pending.isEmpty()) return;
        int sent = dueCount(pending, player.tickCount);
        if (sent == 0) return;
        for (int i = 0; i < sent; i++) player.sendSystemMessage(pending.get(i).line());
        if (sent >= pending.size()) PENDING.remove(id);
        else PENDING.put(id, List.copyOf(pending.subList(sent, pending.size())));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_KEY.remove(id);
        RECENT_MSG_MS.remove(id);
        OFF_POLLS.remove(id);
        // Drop any undelivered lines — tickCount resets on rejoin, and a Dungeon Train death starts a
        // whole new world, so a line held here would otherwise surface in a run it has nothing to do with.
        PENDING.remove(id);
    }
}
