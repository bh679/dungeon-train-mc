package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.PlayerJoinEvents;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last way out of a room whose train is gone: put the player back on the train.
 *
 * <h2>Why this exists</h2>
 * <p>Everything else about a pocket room's way back is about keeping the crossing seamless — the
 * train stands still while somebody is inside it ({@link PortalTrainFreeze}), its carriage group is
 * held loaded ({@link PortalPairResidency}), and a group culled to Sable holding is snatched back
 * ({@link PortalCarriageRevival}). Behind all three sits one state none of them can fix: the group is
 * neither resident nor in holding, so there is nothing to bring back and no corridor in the room leads
 * anywhere.</p>
 *
 * <p>That state used to end in a message — <i>"This room has lost its train. Its corridors lead
 * nowhere."</i> — which is an accurate description of a player sealed in a room that repeats forever
 * with no way out but dying. Being accurate is not the same as being acceptable. A teleport is a worse
 * way to leave a room than walking out of a corridor, and a much better one than not leaving.</p>
 *
 * <h2>Built from the pieces that already put players on trains</h2>
 * <p>{@code /dtp} and the login placement both face the same problem — drop a player onto a moving
 * train from somewhere else entirely — and solved it in {@code DtpPlacementService.tryFinish}. This
 * follows it rather than re-deriving it, which matters most for the part that is not obvious: the
 * {@link SpawnDeckHoldPacket}. A flatbed deck is a Sable sub-level, and a player teleported onto one
 * arrives a few ticks before the client has it; without the hold they fall through the deck they were
 * just rescued onto. The invulnerability window that covers the same moment is the other half of
 * that borrowing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalRoomRescue {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Facing on arrival, matching {@code /dtp}'s: along the train rather than across it. */
    private static final float ARRIVAL_YAW = -90.0f;

    /**
     * Ticks between one carriage group being asked back from holding and the next one being
     * considered — two seconds.
     *
     * <p><b>Without this the rescue un-culls the whole train.</b> {@link
     * PortalCarriageRevival#claimReload} throttles per <i>group</i>, so a tick that finds the group
     * it woke last tick already claimed simply moves on and wakes the next one. And that is the
     * ordinary path, not the failure path: a group reloaded from holding has no settled centre for
     * several ticks, so {@link PlayerJoinEvents#findNearestFlatbedTarget} keeps answering null while
     * it comes up. Asking again at 20 Hz therefore walks the entire train, one disk load and one
     * Sable sub-level spawn per tick, at the exact moment the server is already struggling. Two
     * seconds is long enough for a reload to surface and settle, so the next ask is a real one.</p>
     */
    static final int WAKE_INTERVAL_TICKS = 40;

    /**
     * How many groups one stranding will ever ask back from holding.
     *
     * <p>A ceiling on the cost of a state that may not be recoverable at all. If four groups have
     * been reloaded and none of them settled, a fifth will not be the one that does, and each is
     * chunks and physics the server is carrying for nothing.</p>
     *
     * <p><b>The rescue itself does not give up when this runs out</b> — only the escalation does. The
     * deck lookup still runs every tick (it is cheap precisely when nothing is loaded), so a train
     * that comes back for any other reason still takes the player home. Reset by
     * {@link #forget(int)} when the pair recovers or its occupants are rescued, so the next stranding
     * starts with a full budget.</p>
     */
    static final int MAX_WAKES = 4;

    /**
     * One stranding episode's escalation budget: when this pair last looked for a group to wake, how
     * many it has woken, and whether the two dead-end cases have already been logged.
     */
    private record Wake(long lastTick, int woken, boolean saidDeadEnd) {}

    /** Pair key → its current episode's budget. Server thread only, like the rest of the tick walk. */
    private static final Map<Integer, Wake> WAKES = new HashMap<>();

    /**
     * Rescued player → the tick their invulnerability ends.
     *
     * <p>Only players this class made invulnerable are in here, and only they are ever cleared —
     * a player who was already invulnerable (creative, or an in-flight {@code /dtp}) is left alone
     * rather than having the flag switched off underneath them.</p>
     */
    private static final Map<UUID, Long> INVULNERABLE_UNTIL = new ConcurrentHashMap<>();

    private PortalRoomRescue() {}

    /**
     * Put {@code player} back on the train, or report that there is no train to put them on.
     *
     * <p>Lands on the settled deck nearest the <b>structure's</b> world X rather than the player's.
     * A room is stamped in the chunk columns its carriage occupied when the player walked in, so that
     * is where the train was when they left it — the nearest deck to it is the part of the train they
     * came off, not whichever end happens to be loaded.</p>
     *
     * @return {@code false} when no carriage group has settled anywhere, so the caller can say so and
     *         try again rather than teleporting somebody into empty air
     */
    public static boolean returnToTrain(ServerLevel level, ServerPlayer player,
                                        PortalStructure structure, int pairKey) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        PlayerJoinEvents.FlatbedTarget deck =
            PlayerJoinEvents.findNearestFlatbedTarget(level, data, structure.origin().getX());
        if (deck == null) {
            // No settled carriage anywhere to land on — which is not the rare case it sounds like.
            // A player deep in a room is hundreds of blocks below the track and nowhere near any
            // carriage, so with nothing holding them the WHOLE train is culled, not merely this
            // pair's group. Ask for one back and try again on a later tick.
            wakeATrain(level, pairKey);
            return false;
        }

        double fromX = player.getX(), fromY = player.getY(), fromZ = player.getZ();
        // A vehicle would drag them straight back off the deck, and there is nothing in a pocket room
        // worth arriving on.
        if (player.isPassenger()) player.stopRiding();
        player.teleportTo(level, deck.x(), deck.y(), deck.z(), ARRIVAL_YAW, 0.0f);
        // The deck is a moving sub-level the client does not have yet. Without this the player drops
        // through the floor they were just put on — see the class javadoc.
        DungeonTrainNet.sendTo(player, new SpawnDeckHoldPacket(
            data.getTrainY() + 1.0, SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS));
        holdHarmless(player, level.getGameTime());

        player.displayClientMessage(
            Component.translatable("chat.dungeontrain.portal.returned").withStyle(ChatFormatting.GRAY),
            false);

        // WARN rather than INFO: this is a rescue from a state the freeze and the force-load ticket
        // are supposed to make unreachable, so every occurrence is worth finding in a log afterwards.
        LOGGER.warn("[DungeonTrain] Portal pair {} lost its carriage group entirely — returned {} to "
                + "the train at ({}, {}, {}), from ({}, {}, {}) inside the room.",
            pairKey, player.getName().getString(),
            fmt(deck.x()), fmt(deck.y()), fmt(deck.z()), fmt(fromX), fmt(fromY), fmt(fromZ));
        return true;
    }

    /**
     * Carry {@code player} through the arrival unhurt, for as long as the client-side deck hold runs.
     *
     * <p>The {@link SpawnDeckHoldPacket} is the primary guard and usually the only one that does
     * anything: it stops the client free-falling off a deck sub-level it has not received yet. This
     * is what happens when that is not enough — a hold that lapses before the sub-level arrives drops
     * the player from track height onto the bed below, and the population this runs for is precisely
     * the one where Sable has already been observed running behind. {@code /dtp} makes the same
     * bargain for the same reason ({@code DtpCommand} sets it, {@code DtpPlacementService} clears it);
     * the difference here is that nobody asked for the teleport, so hurting them with it would be
     * doubly unfair.</p>
     *
     * <p>Same length as the hold, and never applied to somebody who was already invulnerable, so this
     * cannot switch off a creative player's flag when it expires.</p>
     */
    private static void holdHarmless(ServerPlayer player, long now) {
        player.resetFallDistance();
        if (player.isInvulnerable()) return;
        player.setInvulnerable(true);
        INVULNERABLE_UNTIL.put(player.getUUID(), now + SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS);
    }

    /**
     * End the invulnerability windows that have run their course.
     *
     * <p>Its own subscriber rather than a call from {@code PortalCarriageEvents.onLevelTick}, which
     * returns early when no player is in the level or portals are switched off — either of which
     * would leave a rescued player invulnerable indefinitely. Same shape as
     * {@code DtpPlacementService}, and a no-op map check when nobody is mid-arrival.</p>
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (INVULNERABLE_UNTIL.isEmpty()) return;

        MinecraftServer server = level.getServer();
        long now = level.getGameTime();
        for (Iterator<Map.Entry<UUID, Long>> it = INVULNERABLE_UNTIL.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now < entry.getValue()) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) player.setInvulnerable(false);
            it.remove();
        }
    }

    /**
     * Drop a leaving player's window, flag and all.
     *
     * <p>The flag is saved to the player's NBT, so a rescue immediately followed by a disconnect —
     * including the disconnect every player gets when the server stops — must not be allowed to
     * write it to disk and hand somebody a permanently invulnerable character.</p>
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (INVULNERABLE_UNTIL.remove(event.getEntity().getUUID()) != null
                && event.getEntity() instanceof ServerPlayer player) {
            player.setInvulnerable(false);
        }
    }

    /**
     * Ask Sable for one of this train's carriage groups back, so there is somewhere to land next
     * tick.
     *
     * <p><b>Why the registry rather than the world.</b> {@code Trains.knownGroups} is grow-only:
     * every group ever spawned keeps its handle there, culled or not, and a stale handle still names
     * its sub-level. That is the only place left to look once nothing is loaded — every train-side
     * lookup that walks <i>loaded</i> carriages answers "there is no train" at exactly this moment.</p>
     *
     * <p>Nearest anchor first, so the player lands as close as possible to where they went in, and
     * only groups Sable still has in holding: one culled before it was ever serialized has no pointer
     * to snatch and cannot be brought back by anybody. Reloading is asynchronous — it surfaces within
     * a few ticks — so this asks and returns, and the caller retries.</p>
     *
     * <p><b>Rate-limited and capped</b>, and the budget check comes before the registry is even
     * copied: this runs from a per-tick path in the state where the server is least able to afford
     * it, and the scan is a defensive copy plus a sort over a grow-only map. See
     * {@link #WAKE_INTERVAL_TICKS} and {@link #MAX_WAKES}.</p>
     */
    private static void wakeATrain(ServerLevel level, int pairKey) {
        long now = level.getGameTime();
        if (!mayWake(pairKey, now)) return;

        UUID trainId = PortalTrainFreeze.trainIdOf(pairKey);
        if (trainId == null) {
            // No group handle was ever recorded for this pair, so there is no train id to look up and
            // nothing to wake. Worth exactly one line: it is the quietest and worst of the outcomes,
            // and it used to produce none at all.
            if (firstDeadEnd(pairKey)) {
                LOGGER.warn("[DungeonTrain] Somebody is stranded in pair {}'s room and no carriage "
                    + "group was ever recorded for it — there is no train to ask back.", pairKey);
            }
            noteWake(pairKey, now, false);
            return;
        }

        Shipyard shipyard = Shipyards.of(level);
        List<Map.Entry<Integer, ManagedShip>> byDistance =
            new ArrayList<>(Trains.knownGroups(trainId).entrySet());
        byDistance.sort(Comparator.comparingInt(e -> Math.abs(e.getKey() - pairKey)));

        for (Map.Entry<Integer, ManagedShip> group : byDistance) {
            ManagedShip ship = group.getValue();
            if (ship == null || ship.isResident()) continue;
            UUID subLevelId = ship.subLevelId();
            if (!shipyard.isHeld(subLevelId)) continue;
            if (!PortalCarriageRevival.claimReload(WAKE_KEY_PREFIX - group.getKey(), subLevelId)) {
                // Already asked for this one and it has not surfaced. Move along the train rather
                // than asking again: re-issuing only re-triggers Sable's snatch-miss error, and a
                // group that will not come back must not be the only one anybody ever waits on.
                continue;
            }
            noteWake(pairKey, now, true);
            int woken = wakesUsed(pairKey);
            if (shipyard.reloadFromHolding(subLevelId)) {
                LOGGER.warn("[DungeonTrain] Nothing of this train is loaded and somebody is stranded "
                        + "in pair {}'s room — reloading group {} (subLevelId={}) from holding to "
                        + "land them on ({} of at most {})",
                    pairKey, group.getKey(), subLevelId, woken, MAX_WAKES);
            }
            if (woken >= MAX_WAKES) {
                LOGGER.warn("[DungeonTrain] Pair {} has had {} carriage groups reloaded from holding "
                        + "and none of them settled — no more will be asked for. The player is still "
                        + "put back on the train if one turns up.", pairKey, woken);
            }
            return;
        }

        if (firstDeadEnd(pairKey)) {
            LOGGER.warn("[DungeonTrain] Pair {}'s room has nobody's train to return to: no carriage "
                + "group is loaded, and none is in holding to be brought back.", pairKey);
        }
        noteWake(pairKey, now, false);
    }

    /**
     * Whether {@link #wakeATrain} may go looking for a group this tick.
     *
     * <p>Package-visible and free of any world so the rule can be unit-tested on its own — the same
     * split {@link PortalCarriageRevival#claimReload} makes for the same reason.</p>
     */
    static boolean mayWake(int pairKey, long now) {
        Wake budget = WAKES.get(pairKey);
        if (budget == null) return true;
        if (budget.woken() >= MAX_WAKES) return false;
        return now - budget.lastTick() >= WAKE_INTERVAL_TICKS;
    }

    /**
     * Record that a scan happened, and whether it woke a group.
     *
     * <p>The tick is recorded either way — a scan that found nothing to wake still cost the copy and
     * the sort, and repeating it every tick is the thing being avoided. Only a scan that actually
     * issued a reload spends budget, so a fruitless one cannot exhaust the cap and leave a group that
     * later enters holding unreachable.</p>
     */
    static void noteWake(int pairKey, long now, boolean wokeOne) {
        Wake budget = WAKES.get(pairKey);
        int woken = (budget == null ? 0 : budget.woken()) + (wokeOne ? 1 : 0);
        WAKES.put(pairKey, new Wake(now, woken, budget != null && budget.saidDeadEnd()));
    }

    /** True the first time this episode reaches a dead end, marking it so the log says so once. */
    static boolean firstDeadEnd(int pairKey) {
        Wake budget = WAKES.get(pairKey);
        if (budget != null && budget.saidDeadEnd()) return false;
        WAKES.put(pairKey, budget == null
            ? new Wake(0L, 0, true)
            : new Wake(budget.lastTick(), budget.woken(), true));
        return true;
    }

    /** How many groups this episode has asked back. For the log line, and for the tests. */
    static int wakesUsed(int pairKey) {
        Wake budget = WAKES.get(pairKey);
        return budget == null ? 0 : budget.woken();
    }

    /**
     * Forget one pair's escalation budget — called when its corridors come back, and when its
     * occupants are carried out, both of which end the episode this budget was counting.
     */
    public static void forget(int pairKey) {
        WAKES.remove(pairKey);
    }

    /**
     * Forget every budget. Called when the server stops, with the rest of the portal state.
     *
     * <p>The invulnerability windows are not ended here — by this point every player has already been
     * disconnected, and {@link #onPlayerLogout} cleared each flag as they went. This only drops what
     * is left so a pair key cannot mean somebody else's train in the next world opened.</p>
     */
    public static void clear() {
        WAKES.clear();
        INVULNERABLE_UNTIL.clear();
    }

    /**
     * Keeps a woken neighbour's reload throttle from colliding with a portal pair's own.
     *
     * <p>{@link PortalCarriageRevival#claimReload} is keyed by pair key, and the group being woken
     * here is somebody else's — often a pair in its own right. Offsetting into a range no carriage
     * index reaches keeps the two conversations apart, so waking a neighbour cannot silence that
     * neighbour's own revival later.</p>
     */
    private static final int WAKE_KEY_PREFIX = Integer.MIN_VALUE / 2;

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
