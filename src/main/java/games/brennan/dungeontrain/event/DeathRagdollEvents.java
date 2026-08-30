package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderCinematicService;
import games.brennan.dungeontrain.compat.SableRagdollBridge;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.AbandonRunPacket;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The death animation: a player's body ragdolls where they fell, and the death screen waits for it.
 *
 * <p><b>How the wait works.</b> {@link LivingDeathEvent} is cancelled at {@link EventPriority#HIGHEST},
 * which means {@code LivingEntity.die()} never runs — the {@code dead} flag stays clear, no
 * combat-kill packet is sent, and the client's death screen does not open. The player is held at 0
 * hearts for {@code deathRagdollHoldTicks} while {@link SableRagdollBridge} tumbles their body, then
 * the <em>same</em> death is replayed with {@code player.die(originalSource)}.</p>
 *
 * <p><b>Why cancel-and-replay rather than a damage hook.</b> Every other DT death handler runs at
 * default or {@code LOW} priority ({@link RunStatsEvents}, {@link SharedCarriageDeathEvents},
 * {@link DeathNoteEvents}, {@link AchievementEvents}, {@code PlayerDataBackupHook},
 * {@code EchoEncounterEvents}), so none of them observe the cancelled first pass: they all fire
 * exactly once, on the replay, with the original {@link DamageSource}. Narrative roll, death stats,
 * relay and Discord reports, kill attribution and inventory handling are therefore identical to a
 * build without this class — only about a second later. Intercepting the fatal <em>damage</em>
 * instead would mean predicting lethality (re-deriving armour/absorption maths) and re-implementing
 * the totem of undying; at this seam a totem'd player never arrives in the first place.</p>
 *
 * <p><b>Two traps this window creates,</b> both handled in {@link #onServerTick}: natural
 * regeneration heals a well-fed player at 0 HP every 10 ticks, so the health is re-pinned every
 * tick; and void damage / {@code /kill} bypass ordinary invulnerability, so those sources are
 * denied a hold entirely rather than being survived through one.</p>
 *
 * <p><b>Everything degrades to "they die, up to a second late."</b> The launch is attempted BEFORE
 * the death is cancelled, so an absent or throwing ragdoll API is an ordinary instant death. If the
 * launch is accepted but the body never assembles (the mod needs a pose snapshot back from the
 * dying player's client), the assemble deadline completes the death anyway. Logout and server
 * shutdown complete the hold synchronously — without that, a player would be saved alive at 0 HP
 * and rejoin un-killable.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeathRagdollEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Extra ticks the body outlives the hold by, so it is never yanked out from under a player who
     * is still watching it settle behind the death screen.
     */
    private static final int DESPAWN_GRACE_TICKS = 20;

    /** One death, paused. Immutable — the tick loop replaces the entry rather than mutating it. */
    private record Held(UUID playerId, DamageSource source, boolean wasInvulnerable,
                        long assembleDeadlineTick, long completeAtTick, boolean assembled) {

        Held assembledAt(long now, int holdTicks) {
            return new Held(playerId, source, wasInvulnerable, assembleDeadlineTick, now + holdTicks, true);
        }
    }

    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();

    /**
     * Players whose held death is being replayed right now. {@code player.die(source)} re-fires
     * {@link LivingDeathEvent}, and this is what stops {@link #onPlayerDeath} from holding it again.
     */
    private static final Set<UUID> COMPLETING = ConcurrentHashMap.newKeySet();

    private DeathRagdollEvents() {}

    /** True while DT is holding this player's death open — read by the ragdoll start-event guard. */
    public static boolean isHeld(UUID playerId) {
        return HELD.containsKey(playerId);
    }

    /**
     * The body assembled (the mod's start event fired). Starts the visible hold from now, so the
     * animation always gets its full length however long the client took to answer.
     */
    public static void markAssembled(UUID playerId) {
        Held held = HELD.get(playerId);
        if (held == null || held.assembled()) return;
        HELD.replace(playerId, held, held.assembledAt(nowTick(), DungeonTrainConfig.getDeathRagdollHoldTicks()));
    }

    /** The body ended early (settled, expired, torn down) — stop waiting and finish the death. */
    public static void onRagdollEnded(UUID playerId) {
        Held held = HELD.get(playerId);
        if (held == null) return;
        completeNow(held);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID id = player.getUUID();
        // The replay of a death we already held, or a second death for a player mid-hold: let it through.
        if (COMPLETING.contains(id) || HELD.containsKey(id)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        DamageSource source = event.getSource();
        if (!shouldHold(DungeonTrainConfig.isDeathRagdollEnabled(),
                DungeonTrainConfig.getDeathRagdollHoldTicks(),
                ModList.get().isLoaded(SableRagdollBridge.RAGDOLL_MOD_ID),
                player.isSpectator() || player.isCreative(),
                BuilderCinematicService.isBuilderLevel(level),
                AbandonRunPacket.isAbandonCause(source),
                source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD),
                player.getY() < level.getMinBuildHeight())) {
            return;
        }

        // Launch FIRST: if the ragdoll can't start, this is an ordinary death with nothing held.
        int despawnTicks = Math.max(DungeonTrainConfig.getDeathRagdollDespawnTicks(),
                DungeonTrainConfig.getDeathRagdollHoldTicks() + DESPAWN_GRACE_TICKS);
        boolean launched;
        try {
            launched = SableRagdollBridge.launch(player, deathVelocity(level, player), despawnTicks);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Sable: Ragdolls present but the launch API is unavailable ({}); "
                    + "deaths are instant.", t.toString());
            launched = false;
        }
        if (!launched) return;

        long now = nowTick();
        HELD.put(id, new Held(id, source, player.isInvulnerable(),
                now + DungeonTrainConfig.getDeathRagdollAssembleTimeoutTicks(),
                now + DungeonTrainConfig.getDeathRagdollHoldTicks(), false));
        event.setCanceled(true);
        // Void and /kill are denied a hold above; this covers everything else that could land
        // during the window, including a second mob hit while the body is still tumbling.
        player.setInvulnerable(true);
        player.setHealth(0.0F);
    }

    /**
     * Whether a death qualifies for the ragdoll hold. Pure, so the deny list can be tested without
     * a server — see {@code DeathRagdollGateTest}.
     *
     * @param bypassCause {@code /kill} or the void: both bypass invulnerability, so a hold could
     *                    not protect the player through it, and neither has anything to watch.
     */
    static boolean shouldHold(boolean enabled, int holdTicks, boolean modLoaded, boolean spectatorOrCreative,
                              boolean builderLevel, boolean abandonCause, boolean bypassCause, boolean belowWorld) {
        return enabled
                && holdTicks > 0
                && modLoaded
                && !spectatorOrCreative
                && !builderLevel
                && !abandonCause
                && !bypassCause
                && !belowWorld;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (HELD.isEmpty()) return;
        long now = event.getServer().getTickCount();
        List<Held> due = new ArrayList<>();
        for (Held held : HELD.values()) {
            ServerPlayer player = playerOf(event.getServer(), held);
            if (player == null || player.isRemoved()) {
                HELD.remove(held.playerId(), held);   // nothing left to kill
                continue;
            }
            // Natural regeneration heals a well-fed player at 0 HP every 10 ticks, which would
            // silently resurrect them mid-animation. Re-pin every tick, not once.
            if (player.getHealth() > 0.0F) player.setHealth(0.0F);
            boolean overdue = held.assembled() ? now >= held.completeAtTick() : now >= held.assembleDeadlineTick();
            if (overdue) due.add(held);
        }
        for (Held held : due) completeNow(held);
    }

    /** A player who logs out mid-hold must be saved DEAD, or they rejoin alive at 0 hearts. */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Held held = HELD.get(player.getUUID());
        if (held != null) completeNow(held);
    }

    /** Same reason as logout: the save must not contain a held death. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Held held : new ArrayList<>(HELD.values())) completeNow(held);
    }

    /**
     * End the hold and let the death land: the entry is removed FIRST, so the replayed
     * {@link LivingDeathEvent} — and the ragdoll-end event that {@link SableRagdollBridge#end}
     * fires — can't re-enter this method.
     */
    private static void completeNow(Held held) {
        if (!HELD.remove(held.playerId(), held)) return;   // someone else already completed it
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(held.playerId());
        if (player == null) return;

        player.setInvulnerable(held.wasInvulnerable());
        try {
            SableRagdollBridge.end(player);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] could not end the death ragdoll for {} ({}); dying anyway.",
                    player.getGameProfile().getName(), t.toString());
        }

        COMPLETING.add(held.playerId());
        try {
            player.die(held.source());
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] replaying a held death failed; killing outright", t);
            player.setHealth(0.0F);
        } finally {
            COMPLETING.remove(held.playerId());
        }
    }

    /**
     * The velocity to launch the body with: the player's own motion plus, when they died aboard a
     * carriage, that carriage's world velocity. Without the carriage term the body is world-space
     * and the train simply leaves it behind — through the rear wall — within a few ticks.
     */
    private static Vec3 deathVelocity(ServerLevel level, ServerPlayer player) {
        Vec3 own = player.getDeltaMovement();
        try {
            List<Trains.Carriage> carriages = Trains.allCarriages(level);
            if (carriages.isEmpty()) return own;
            Trains.Carriage carriage = CarriageDeck.carriageUnder(carriages, player);
            if (carriage == null) return own;
            // The driver's own target velocity, not the config constant: a paused, stopped or
            // builder-world train reports zero and the body stays put, as it should.
            if (!(carriage.ship().getKinematicDriver() instanceof TrainTransformProvider driver)) return own;
            Vector3dc v = driver.getTargetVelocity();
            return own.add(v.x(), v.y(), v.z());
        } catch (Throwable t) {
            return own;   // a launch that is merely mis-aimed beats no death animation at all
        }
    }

    private static ServerPlayer playerOf(MinecraftServer server, Held held) {
        return server.getPlayerList().getPlayer(held.playerId());
    }

    private static long nowTick() {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0L : server.getTickCount();
    }
}
