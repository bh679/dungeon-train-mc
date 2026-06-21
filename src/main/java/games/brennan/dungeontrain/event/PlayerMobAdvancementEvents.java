package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.advancement.PlayerMobSocialTracker;
import games.brennan.dungeontrain.compat.EchoIdentity;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wires PlayerMob-interaction signals to the social/aggression advancement
 * triggers in {@link ModAdvancementTriggers}.
 *
 * <ul>
 *   <li><b>The Denamed</b> — {@link PlayerInteractEvent.EntityInteract}: using
 *       a custom-named name tag on a PlayerMob fires {@code named_playermob}.</li>
 *   <li><b>Reboarder</b> — {@link AttackEntityEvent} stamps the most recent
 *       player-strike on each PlayerMob (with whether it was on the train at
 *       the time); the {@link LevelTickEvent.Post} scan fires
 *       {@code pushed_playermob_off_train} when a recently-struck mob that was
 *       on the train has since left the train footprint. The recent-hit gate
 *       distinguishes a deliberate shove from the PlayerMob's own by-design
 *       falls (it has recovery AI that climbs back on).</li>
 *   <li>Logout — clears the player's transient {@link PlayerMobSocialTracker}
 *       state.</li>
 * </ul>
 *
 * <p>Item give/receive (A Silent Friend / Friends) is captured separately via
 * PlayerMob's {@code PlayerMobSocialHooks} gift seam, forwarded by
 * {@code compat.PlayerMobSocialBridge}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerMobAdvancementEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Entity-id namespace of the bundled playermob mod (mirrors {@code TrainTickEvents}). */
    private static final String PLAYERMOB_NAMESPACE = "playermob";

    /** Off-train scan cadence — matches {@link BoardingProgressEvents}. */
    private static final int SCAN_PERIOD_TICKS = 10;

    /** How long after a player's strike a mob leaving the deck still counts as "pushed off". */
    private static final long PUSH_WINDOW_TICKS = 200L;

    /**
     * Consecutive off-deck scans required before crediting a push. One scan of
     * tolerance ({@code offScans > 2} ⇒ the 3rd off sample, ≈30 ticks) absorbs a
     * mob's ordinary airborne frames (a hop on the deck) so only a real
     * departure from the carriage counts.
     */
    private static final int OFF_DECK_GRACE_SCANS = 2;

    /** Echo-proximity scan cadence (ticks) for the {@code Echo Encounter} advancement. */
    private static final int ECHO_SCAN_PERIOD_TICKS = 10;

    /** "Within 2 blocks" range (centre-to-centre) for the {@code Echo Encounter} advancement. */
    private static final double ECHO_NEAR_RADIUS = 2.0;

    /** mob UUID → most recent player strike. */
    private static final Map<UUID, ReboarderHit> RECENT_HITS = new HashMap<>();

    private PlayerMobAdvancementEvents() {}

    /**
     * A player strike on a PlayerMob plus the rolling state the off-deck scan
     * carries between samples. Package-private (with {@link ReboarderDecision} /
     * {@link ReboarderStep} / {@link #step}) so the pure decision logic is
     * table-testable without a Minecraft bootstrap.
     *
     * @param wasOnTrain mob was within a carriage footprint at strike time
     *                   (lenient AABB capture — see {@link #onAttackEntity})
     * @param lastOnDeck most recent live scan saw it supported by a carriage block
     * @param offScans   consecutive live scans observed off the deck (grace counter)
     */
    record ReboarderHit(UUID playerUuid, long tick, boolean wasOnTrain,
                        boolean lastOnDeck, int offScans) {}

    /** What one scan concludes about a tracked hit. */
    enum ReboarderDecision { CREDIT, KEEP, DROP }

    /** A decision plus the hit state to persist when the decision is {@code KEEP}. */
    record ReboarderStep(ReboarderDecision decision, ReboarderHit next) {}

    // ---------------- The Denamed ----------------

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!isPlayerMob(event.getTarget())) return;
        var stack = event.getItemStack();
        if (!stack.is(Items.NAME_TAG)) return;
        // Vanilla only renames when the name tag carries a custom name (set on
        // an anvil); match that so a blank name tag doesn't grant the award.
        if (!stack.has(DataComponents.CUSTOM_NAME)) return;
        ModAdvancementTriggers.NAMED_PLAYERMOB.get().trigger(player);
    }

    // ---------------- Reboarder ----------------

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!isPlayerMob(target)) return;
        if (!(target.level() instanceof ServerLevel level)) return;
        // Lenient capture: the mob was on/around the train when struck. The
        // precise "pushed off the deck" decision is made per-scan in onLevelTick
        // via isOnCarriageDeck — see step().
        boolean onTrain = CarriageDeck.isOnTrainFootprint(Trains.allCarriages(level), target);
        RECENT_HITS.put(target.getUUID(), new ReboarderHit(
            player.getUUID(), level.getGameTime(), onTrain, true, 0));
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        if (RECENT_HITS.isEmpty()) return;

        long now = level.getGameTime();
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        // Snapshot so we can mutate the map while iterating.
        for (Map.Entry<UUID, ReboarderHit> entry : new ArrayList<>(RECENT_HITS.entrySet())) {
            UUID mobUuid = entry.getKey();
            ReboarderHit hit = entry.getValue();
            Entity mob = level.getEntity(mobUuid);

            boolean expired = now - hit.tick() > PUSH_WINDOW_TICKS;
            boolean dead = mob != null && !mob.isAlive();
            // null ⇒ unloaded or dead (no live position); otherwise its support.
            Boolean onDeck = (mob != null && mob.isAlive())
                ? CarriageDeck.isOnCarriageDeck(carriages, mob) : null;

            ReboarderStep outcome = step(hit, onDeck, dead, expired);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[DungeonTrain] Reboarder mob={} onDeck={} dead={} expired={} "
                        + "wasOnTrain={} lastOnDeck={} offScans={} -> {}",
                    mobUuid, onDeck, dead, expired, hit.wasOnTrain(),
                    hit.lastOnDeck(), hit.offScans(), outcome.decision());
            }
            switch (outcome.decision()) {
                case CREDIT -> {
                    if (creditPush(level, hit.playerUuid()) || expired) {
                        RECENT_HITS.remove(mobUuid);
                    }
                    // else: puncher briefly offline, window open → retry next scan.
                }
                case DROP -> RECENT_HITS.remove(mobUuid);
                case KEEP -> RECENT_HITS.put(mobUuid, outcome.next());
            }
        }
    }

    /**
     * Pure decision for one off-deck scan of a tracked hit — side-effect-free so
     * {@code PlayerMobReboarderTest} can table-test it.
     *
     * @param onDeck  mob's current carriage support, or {@code null} when it is
     *                unloaded or dead (no live position to test)
     * @param dead    the mob exists but is no longer alive
     * @param expired the push window has elapsed since the strike
     */
    static ReboarderStep step(ReboarderHit hit, Boolean onDeck, boolean dead, boolean expired) {
        if (onDeck != null) {                            // live: we can see where it is
            if (hit.wasOnTrain() && !onDeck) {           // off the carriage deck right now
                int off = hit.offScans() + 1;
                if (off > OFF_DECK_GRACE_SCANS) {
                    return new ReboarderStep(ReboarderDecision.CREDIT, hit);
                }
                return new ReboarderStep(ReboarderDecision.KEEP, new ReboarderHit(
                    hit.playerUuid(), hit.tick(), hit.wasOnTrain(), false, off));
            }
            // On the deck (or never aboard): reset the off-streak; drop at window end.
            if (expired) return new ReboarderStep(ReboarderDecision.DROP, hit);
            return new ReboarderStep(ReboarderDecision.KEEP, new ReboarderHit(
                hit.playerUuid(), hit.tick(), hit.wasOnTrain(), onDeck, 0));
        }
        // Unloaded or dead: decide from the last live observation.
        if (hit.wasOnTrain() && !hit.lastOnDeck()) {     // fell off the deck, then died/unloaded
            return new ReboarderStep(ReboarderDecision.CREDIT, hit);
        }
        if (expired || dead) {                           // killed on the deck, or unloaded aboard
            return new ReboarderStep(ReboarderDecision.DROP, hit);
        }
        return new ReboarderStep(ReboarderDecision.KEEP, hit); // null, window open → wait for reload
    }

    /**
     * Fire the Reboarder trigger for {@code playerUuid} if they're online.
     *
     * @return {@code true} once the trigger fired; {@code false} if the puncher
     *         is briefly offline so the caller can retry within the window.
     */
    private static boolean creditPush(ServerLevel level, UUID playerUuid) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerUuid);
        if (player == null) return false;
        ModAdvancementTriggers.PUSHED_PLAYERMOB_OFF_TRAIN.get().trigger(player);
        return true;
    }

    // ---------------- That's What Friends Are For ----------------

    /**
     * Fires when the player kills a hostile mob that is currently targeting a
     * PlayerMob — coming to a passenger's defence. A mob keeps its target
     * through death, so checking {@code getTarget()} in the death event
     * reliably catches the "kill the thing attacking a passenger" case.
     * Mirrors the kill-credit shape of {@code RunStatsEvents.onMobKilled}.
     */
    @SubscribeEvent
    public static void onMobKilled(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (!(victim instanceof Mob mob)) return;
        if (isPlayerMob(mob.getTarget())) {
            ModAdvancementTriggers.DEFENDED_PLAYERMOB.get().trigger(player);
        }
    }

    // ---------------- Echo Encounter ----------------

    /**
     * Periodic scan: grant <em>Echo Encounter</em> when a player comes within
     * {@link #ECHO_NEAR_RADIUS} blocks of {@linkplain EchoIdentity#isOwnEcho
     * their own echo} — a PlayerMob reincarnated from one of <em>that</em>
     * player's past lives. Cheap: one padded-AABB entity query per player on a
     * {@link #ECHO_SCAN_PERIOD_TICKS} cadence, narrowed by a centre-to-centre
     * distance check. Re-firing for an already-granted player is a vanilla no-op.
     */
    @SubscribeEvent
    public static void onEchoProximityScan(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.getGameTime() % ECHO_SCAN_PERIOD_TICKS != 0) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        double maxDistSq = ECHO_NEAR_RADIUS * ECHO_NEAR_RADIUS;
        for (ServerPlayer player : players) {
            if (player.isSpectator()) continue;
            AABB near = player.getBoundingBox().inflate(ECHO_NEAR_RADIUS);
            for (PlayerMobEntity echo : level.getEntitiesOfClass(
                    PlayerMobEntity.class, near, mob -> EchoIdentity.isOwnEcho(mob, player.getUUID()))) {
                if (player.distanceToSqr(echo) <= maxDistSq) {
                    ModAdvancementTriggers.ENCOUNTERED_ECHO.get().trigger(player);
                    break;
                }
            }
        }
    }

    // ---------------- Lay to Rest (kill your own echo) ----------------

    /**
     * Fires when a player kills {@linkplain EchoIdentity#isOwnEcho their own
     * echo}. Distinct from <em>Murderous Intent</em> (any PlayerMob) — keyed on
     * the victim being a reincarnation of this same player's past life, so it
     * grants alongside the generic kill.
     */
    @SubscribeEvent
    public static void onEchoKilled(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (EchoIdentity.isOwnEcho(victim, player.getUUID())) {
            ModAdvancementTriggers.KILLED_ECHO.get().trigger(player);
        }
    }

    // ---------------- Logout cleanup ----------------

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerMobSocialTracker.forget(player.getUUID());
    }

    // ---------------- Helpers ----------------

    private static boolean isPlayerMob(Entity entity) {
        return entity != null
            && PLAYERMOB_NAMESPACE.equals(EntityType.getKey(entity.getType()).getNamespace());
    }
}
