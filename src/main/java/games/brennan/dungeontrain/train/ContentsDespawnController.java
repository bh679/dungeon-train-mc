package games.brennan.dungeontrain.train;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distance gate for <i>despawning</i> carriage contents mobs — the counterpart to the spawn gate in
 * {@code TrainCarriageAppender.tickPendingEntitySpawnDistanceGate}.
 *
 * <h2>The gap this fills</h2>
 * Contents mobs are stamped {@code PersistenceRequired=true} by {@link CarriageContentsPlacer}, which
 * disables vanilla's distance despawn, and Sable tickets a resident sub-level's chunks at
 * {@code ENTITY_TICKING}. So every mob in every carriage <i>behind</i> the player kept running full
 * AI every tick until the rolling window happened to recycle that slot.
 * {@code PhysicsFreezeController} parks the carriage body but explicitly excludes contents-tagged
 * entities from its liveness test, so the passengers were invisible to it. Carriages ahead of the
 * player already cost nothing thanks to the spawn gate; this makes carriages behind cost nothing too.
 *
 * <h2>Three-state lifecycle</h2>
 * A group is in exactly one of PENDING (contents not spawned yet), LIVE, or SNAPSHOT. The spawn gate
 * owns PENDING → LIVE; this controller owns LIVE ⇄ SNAPSHOT and skips any group whose pending array
 * is still set. The two gates therefore act on disjoint edges and cannot fight each other.
 *
 * <h2>Hysteresis</h2>
 * Eager restore, lazy despawn — the same asymmetry as {@code PhysicsFreezeController}. Restore fires
 * the instant a player is within {@link #RESTORE_RADIUS_BLOCKS}; despawn waits for
 * {@link #AWAY_GRACE_TICKS} consecutive ticks beyond {@link #DESPAWN_RADIUS_BLOCKS}. Between the two
 * radii nothing happens in either direction, so a player pacing the boundary generates no work.
 */
public final class ContentsDespawnController {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /**
     * Master switch, mirroring {@code PhysicsFreezeController.ENABLED}. Turning it off restores every
     * held snapshot on the following ticks — see the {@code !enabled} branch of {@link #decide}.
     * Toggle with {@code /dungeontrain debug contentsdespawn <on|off|status>}.
     */
    public static volatile boolean ENABLED = true;

    /**
     * Restore radius. Aliased to the spawn gate's radius rather than copied, so a player walking up
     * to a carriage sees identical behaviour whether it is the carriage's first visit or its fifth,
     * and so the two numbers cannot drift apart in a later edit.
     */
    static final double RESTORE_RADIUS_BLOCKS = TrainCarriageAppender.SPAWN_RADIUS_BLOCKS;
    static final double RESTORE_RADIUS_SQ = RESTORE_RADIUS_BLOCKS * RESTORE_RADIUS_BLOCKS;

    /**
     * Despawn radius — how far every player must be before a group's mobs are swept.
     *
     * <p>60 blocks is deliberately <i>inside</i> vanilla mob client-tracking range (most hostiles
     * track at 8 chunks = 128 blocks, villagers at 10 = 160), so unlike a wider radius this sweep is
     * not structurally invisible: a player who looks back at a carriage 60+ blocks away can see its
     * mobs blink out, and blink back in on approach. That is an accepted trade — on a straight ride
     * the swept carriages are behind the player, and reclaiming their AI cost this early is the whole
     * point of the gate.</p>
     *
     * <p>Because the band between this and {@link #RESTORE_RADIUS_BLOCKS} is only 12 blocks — less
     * than a carriage group's own footprint, and under a second of travel — the geometry no longer
     * prevents sweep/restore churn on its own. {@link #AWAY_GRACE_TICKS} is what does. If
     * {@code [despawn] restored=} stays non-zero on a steady one-directional ride, raise the grace
     * window rather than this radius.</p>
     */
    static final double DESPAWN_RADIUS_BLOCKS = 60.0;
    static final double DESPAWN_RADIUS_SQ = DESPAWN_RADIUS_BLOCKS * DESPAWN_RADIUS_BLOCKS;

    /**
     * Consecutive ticks beyond the despawn radius before a sweep. 100t = 5s.
     *
     * <p><b>This is the thrash guard</b>, and it is load-bearing: with only a 12-block hysteresis
     * band the geometry cannot stop a player oscillating across the boundary, so this window is what
     * bounds a full DESPAWN → RESTORE → DESPAWN cycle however quickly the player moves.</p>
     *
     * <p>Raised from 60t after a dev-client ride showed real churn — {@code pIdx=54} swept 7 mobs,
     * restored 5s later, swept the same 7 again 12s after that, twice inside 33s. A player walking
     * the length of the train crosses the 12-block band in about 3s, which the old 3s window could
     * not out-wait. 5s clears the observed 5–7s return times while still sweeping a genuinely
     * abandoned carriage promptly — negligible against a ride where carriages stay behind the player
     * for minutes.</p>
     *
     * <p>If churn persists at this value, suspect the <i>measurement</i> rather than the window:
     * distance is taken to {@link TrainTransformProvider#getCanonicalPos}, which sits at the
     * back-pad edge of a ~37-block group, so one end of a group can be much nearer than its anchor
     * suggests. The fix would be measuring to the nearest point of the group's AABB, not a longer
     * grace period.</p>
     */
    static final int AWAY_GRACE_TICKS = 100;

    /**
     * Cap on sweeps per tick. A sweep is the only expensive operation here (one AABB entity query,
     * N entity serialisations, one compressed file write), so a backlog of abandoned carriages drains
     * over about a second instead of spiking a single tick. The distance test itself is unbudgeted —
     * it is the same handful of double operations per group as the spawn gate.
     */
    static final int MAX_SWEEPS_PER_TICK = 2;

    /** Restores are cheap and latency-sensitive (a player is standing right there), so a wider cap. */
    static final int MAX_RESTORES_PER_TICK = 4;

    private static final int LOG_PERIOD_TICKS = 40;

    /**
     * Entity id of the bundled PlayerMob. Duplicated as a String rather than reached for from
     * {@code PlayerMobGroupSpawner} / {@code DeathNoteEchoSpawner} (where it is private) so the
     * exemption predicate stays a pure function over strings and can be unit-tested.
     */
    static final String PLAYER_MOB_ID = "playermob:player_mob";

    /** Persistent-data key prefix shared by every {@code DeathNoteEchoSpawner} marker. */
    static final String DEATH_NOTE_KEY_PREFIX = "dt_deathnote_";

    /**
     * Ticks a restore may be blocked by a frozen carriage before we complain. Freeze unblocks within
     * a tick or two in normal operation, so a sustained block means the freeze controller and this
     * one disagree about what is near — worth a log line rather than silent invisible mobs.
     */
    private static final int RESTORE_STARVED_WARN_TICKS = 60;

    /** Levels whose stale previous-session snapshot files have already been reclaimed. */
    private static final Set<ResourceKey<Level>> PRUNED = ConcurrentHashMap.newKeySet();

    // Diagnostics snapshot for the status command and the [despawn] line.
    private static volatile int lastGroups;
    private static volatile int lastAway;
    private static volatile int lastSnapshotted;
    private static volatile int lastEntitiesHeld;
    private static int sweptWindow;
    private static int restoredWindow;

    private ContentsDespawnController() {}

    enum Action { NONE, DESPAWN, RESTORE }

    /**
     * Pure decision core — no Minecraft types, so it is unit-testable without a game bootstrap.
     *
     * @param enabled     master switch
     * @param distSq      squared distance from the group anchor to the NEAREST player
     * @param live        contents are currently spawned in the level
     * @param snap        contents are currently held in a snapshot
     * @param ticksBeyond consecutive ticks beyond {@link #DESPAWN_RADIUS_BLOCKS}
     */
    static Action decide(boolean enabled, double distSq, boolean live, boolean snap, int ticksBeyond) {
        // Off means "put everything back and never take anything away" — the toggle has to be able to
        // fully undo itself, otherwise a misbehaving gate could not be switched off mid-session.
        if (!enabled) return snap ? Action.RESTORE : Action.NONE;
        // Eager: a player is close enough to see the carriage, so no grace period.
        if (snap) return distSq <= RESTORE_RADIUS_SQ ? Action.RESTORE : Action.NONE;
        // Nothing spawned yet (the spawn gate owns this group) or an empty carriage.
        if (!live) return Action.NONE;
        // Lazy: strictly beyond the radius AND settled there.
        return (distSq > DESPAWN_RADIUS_SQ && ticksBeyond >= AWAY_GRACE_TICKS) ? Action.DESPAWN : Action.NONE;
    }

    /**
     * Pure exemption predicate — which entities the sweep is allowed to touch.
     *
     * <p>PlayerMobs and Death Note echoes carry the same contents tag as ordinary carriage mobs but
     * are deliberately left live: they are few, they march off the carriage by design via
     * {@code TrainConfinement}, and echoes hold UUID-keyed registrations in
     * {@code DeathNoteEchoController} / {@code RemoteEchoEncounters}. Exempting PlayerMobs also means
     * a restore can never re-run the one-in-N {@code PlayerMobGroupSpawner} roll that rides on the
     * spawn gate.</p>
     *
     * @param entityTypeId       registry id, e.g. {@code minecraft:villager}
     * @param tags               the entity's scoreboard tags (null-safe)
     * @param persistentDataKeys keys of the entity's persistent data compound (null-safe)
     */
    static boolean isSweepable(String entityTypeId, Collection<String> tags, Collection<String> persistentDataKeys) {
        if (tags == null) return false;
        boolean onTrain = false;
        for (String tag : tags) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) {
                onTrain = true;
                break;
            }
        }
        if (!onTrain) return false;
        if (PLAYER_MOB_ID.equals(entityTypeId)) return false;
        if (persistentDataKeys != null) {
            for (String key : persistentDataKeys) {
                // Any death-note marker exempts, regardless of entity type — an echo's identity lives
                // in this data, not in its type.
                if (key.startsWith(DEATH_NOTE_KEY_PREFIX)) return false;
            }
        }
        return true;
    }

    /**
     * Per-tick reconcile. Called from {@code TrainTickEvents} immediately AFTER
     * {@code PhysicsFreezeController.reconcile} — a carriage coming back into range is unfrozen by
     * that pass first, so its pose is already recovering by the time a restore looks at it.
     *
     * @param trainsById the map the caller already built; reused so this does not re-scan the shipyard
     */
    public static void reconcile(ServerLevel level,
                                 Map<UUID, List<Trains.Carriage>> trainsById,
                                 List<ServerPlayer> players) {
        if (PRUNED.add(level.dimension())) {
            // First reconcile for this level: nothing this session can have written a snapshot yet,
            // so anything on disk is a leftover from a crashed previous session.
            ContentsSnapshotStore.pruneStaleFromPreviousSession(level);
        }
        if (players.isEmpty()) return;

        int groups = 0;
        int away = 0;
        int snapshotted = 0;
        int entitiesHeld = 0;
        int swept = 0;
        int restored = 0;

        for (List<Trains.Carriage> train : trainsById.values()) {
            for (Trains.Carriage carriage : train) {
                TrainTransformProvider provider = carriage.provider();
                if (!provider.isPlacedSuccessfully()) continue;
                // The spawn gate still owns this group — it has never had contents in the level.
                if (provider.hasPendingContentsEntitySpawns()) continue;
                if (!carriage.ship().isResident()) continue;

                Vector3dc pos = provider.getCanonicalPos();
                if (pos == null) continue;

                groups++;
                double distSq = nearestPlayerDistSq(players, pos);
                if (distSq > DESPAWN_RADIUS_SQ) {
                    away++;
                    provider.setContentsAwayTicks(provider.getContentsAwayTicks() + 1);
                } else {
                    provider.setContentsAwayTicks(0);
                }

                boolean snap = provider.isContentsDespawned();
                if (snap) {
                    snapshotted++;
                    entitiesHeld += provider.getContentsSnapshotSize();
                }

                switch (decide(ENABLED, distSq, !snap, snap, provider.getContentsAwayTicks())) {
                    case DESPAWN -> {
                        if (swept >= MAX_SWEEPS_PER_TICK) break;
                        int captured = ContentsEntitySnapshot.captureAndDiscard(level, carriage);
                        if (captured < 0) break; // sweep abandoned; entities untouched
                        provider.setContentsDespawned(true);
                        swept++;
                        snapshotted++;
                        entitiesHeld += captured;
                        LOGGER.debug("[despawn] swept pIdx={} entities={} distSq={}",
                            provider.getPIdx(), captured, String.format("%.0f", distSq));
                    }
                    case RESTORE -> {
                        if (restored >= MAX_RESTORES_PER_TICK) break;
                        // Once a restore has been blocked this long the freeze is not going to clear
                        // on its own, so stop waiting: a mob placed at the carriage's parked pose is
                        // strictly better than a mob that never comes back.
                        boolean force = provider.getContentsRestoreDeferredTicks() >= RESTORE_STARVED_WARN_TICKS;
                        int count = ContentsEntitySnapshot.restore(level, carriage, force);
                        if (count < 0) {
                            // Deferred — almost always a still-frozen carriage whose pose has not
                            // caught up. Retried next tick; complain once when it looks stuck.
                            int deferred = provider.getContentsRestoreDeferredTicks() + 1;
                            provider.setContentsRestoreDeferredTicks(deferred);
                            if (deferred == RESTORE_STARVED_WARN_TICKS) {
                                LOGGER.warn("[despawn] restore starved for pIdx={} — carriage still frozen after {} ticks; forcing next tick",
                                    provider.getPIdx(), RESTORE_STARVED_WARN_TICKS);
                            }
                            break;
                        }
                        provider.setContentsDespawned(false);
                        provider.setContentsAwayTicks(0);
                        provider.setContentsRestoreDeferredTicks(0);
                        restored++;
                        snapshotted--;
                        entitiesHeld -= count;
                        LOGGER.debug("[despawn] restored pIdx={} entities={}", provider.getPIdx(), count);
                    }
                    case NONE -> { }
                }
            }
        }

        lastGroups = groups;
        lastAway = away;
        lastSnapshotted = Math.max(0, snapshotted);
        lastEntitiesHeld = Math.max(0, entitiesHeld);
        sweptWindow += swept;
        restoredWindow += restored;

        if (lastSnapshotted > 0 && level.getGameTime() % LOG_PERIOD_TICKS == 0) {
            LOGGER.debug("[despawn] dim={} groups={} away={} snapshotted={} entitiesHeld={} swept={} restored={}",
                level.dimension().location(), lastGroups, lastAway, lastSnapshotted, lastEntitiesHeld,
                sweptWindow, restoredWindow);
            sweptWindow = 0;
            restoredWindow = 0;
        }
    }

    /** Squared distance to the closest player. Tests ALL players — a group is live if ANY player is near. */
    private static double nearestPlayerDistSq(List<ServerPlayer> players, Vector3dc pos) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            best = Math.min(best, player.distanceToSqr(pos.x(), pos.y(), pos.z()));
        }
        return best;
    }

    public static int lastGroups() { return lastGroups; }
    public static int lastAway() { return lastAway; }
    public static int lastSnapshotted() { return lastSnapshotted; }
    public static int lastEntitiesHeld() { return lastEntitiesHeld; }
}
