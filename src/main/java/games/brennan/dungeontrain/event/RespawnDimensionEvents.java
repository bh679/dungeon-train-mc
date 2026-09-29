package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Rolls a random starting dimension when the player clicks the vanilla
 * "Respawn" button on the death screen. 1% End / 5% Nether / 94% Overworld —
 * the same distribution {@link games.brennan.dungeontrain.client.DeathScreenLayoutHandler}
 * uses for the "New World" / "Same World" buttons, but consumed server-side
 * since vanilla respawn keeps the player in the existing world instance.
 *
 * <p>If the rolled dimension differs from where vanilla just placed the
 * player, this handler puts them on that dimension's train
 * ({@link #placeOrWake}), which is one of three things:</p>
 * <ol>
 *   <li>A settled deck is loaded — teleport straight onto it.</li>
 *   <li>The dimension has a train but none of it is loaded
 *       ({@link TrainBootstrapEvents#trainKnownIn}) — ask one held group back
 *       from Sable holding ({@link TrainCarriageAppender#wakeHeldGroup}), park
 *       the player invulnerable at the ground pose, and let
 *       {@link DtpPlacementService} land them on the deck once it settles.
 *       <b>Never spawn here</b>: a second seed under a sleeping train is the
 *       overlapping-trains bug.</li>
 *   <li>The dimension has never had a train — lay down a seed via
 *       {@link TrainBootstrapEvents#ensureTrainSpawned} and place beside it.
 *       The per-tick appender extends it at gameplay speed afterward.</li>
 * </ol>
 *
 * <p>Also handles the <b>End exit portal</b>: after killing the dragon and
 * dropping into the portal, vanilla puts the player (inventory kept) at their
 * bed / world spawn — in a Dungeon Train world that is an empty vanilla
 * Overworld with no train anywhere near. Instead, the player is placed back on
 * the train in the world's starting dimension, exactly like login and the
 * cross-dim respawn above. No dimension roll on this path. Vanilla reaches the
 * Overworld two different ways, so both are hooked:</p>
 * <ul>
 *   <li><b>First time</b> ({@code seenCredits} false) — the credits roll and the
 *       client's "respawn" afterwards goes through {@code PlayerList.respawn},
 *       i.e. {@link PlayerEvent.PlayerRespawnEvent#isEndConquered()}.</li>
 *   <li><b>Every later time</b> — {@code EndPortalBlock} is a plain dimension
 *       change to the respawn position, so only
 *       {@link PlayerEvent.PlayerChangedDimensionEvent} (End → Overworld) fires.</li>
 * </ul>
 *
 * <p>Skip-rules:</p>
 * <ul>
 *   <li>Hardcore mode — vanilla kicks the player to spectator immediately
 *       after this event fires; teleporting first then being kicked is a
 *       jarring no-op.</li>
 *   <li>{@code !data.startsWithTrain()} — the world has the auto-train
 *       system disabled; respect that choice.</li>
 *   <li>Rolled dimension already matches the player's current respawn dim —
 *       vanilla flow handles it. Most respawns hit this branch (94% Overworld
 *       on an Overworld-started world).</li>
 *   <li>Rolled dimension's {@code ServerLevel} not loaded — defensive guard
 *       for the unlikely case a datapack removed a vanilla dimension.</li>
 * </ul>
 *
 * <p>Server-side; common-scope so dedicated servers also run it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RespawnDimensionEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RespawnDimensionEvents() {}

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        // End exit portal, credits already seen — no respawn event on this path.
        if (event.getFrom() != Level.END || event.getTo() != Level.OVERWORLD) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Only a real portal transit: Entity#handlePortal arms the cooldown right
        // before changeDimension. DT's own End → Overworld teleports (login
        // placement of a player saved in the End, the rolled respawn above,
        // /dtp) never do, and must not bounce the player back to the End train.
        if (player.portalProcess == null || !player.isOnPortalCooldown()) return;
        MinecraftServer server = player.getServer();
        if (server == null || server.isHardcore()) return;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        if (!data.startsWithTrain()) return;
        returnToTrainAfterEnd(player, server, data);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null || server.isHardcore()) return;

        ServerLevel overworld = server.overworld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return;

        if (event.isEndConquered()) {
            // End exit portal, first time (credits path).
            returnToTrainAfterEnd(player, server, data);
            return;
        }

        StartingDimension rolled = StartingDimension.rollRespawnDimension(
                overworld.random.nextDouble());
        LOGGER.info("[DungeonTrain] Respawn rolled startingDimension={} for {}",
                rolled, player.getName().getString());

        if (player.serverLevel().dimension() == rolled.levelKey()) return;

        ServerLevel target = server.getLevel(rolled.levelKey());
        if (target == null) {
            LOGGER.warn("[DungeonTrain] Rolled dim {} not loaded — vanilla respawn flow", rolled);
            return;
        }

        placeOrWake(player, target, data, "Respawn");
    }

    /**
     * End exit portal: vanilla has just put the player at their bed / world
     * spawn. Move them onto the train in the starting dimension instead —
     * the same landing the game opens with.
     */
    private static void returnToTrainAfterEnd(
            ServerPlayer player, MinecraftServer server, DungeonTrainWorldData data) {
        StartingDimension startingDim = data.startingDimension();
        ServerLevel target = server.getLevel(startingDim.levelKey());
        if (target == null) {
            LOGGER.warn("[DungeonTrain] End conquered but starting dim {} not loaded — vanilla flow for {}",
                    startingDim, player.getName().getString());
            return;
        }
        LOGGER.info("[DungeonTrain] End conquered — returning {} to the train in {}",
                player.getName().getString(), startingDim);
        placeOrWake(player, target, data, "End conquered");
    }

    /** What {@link #placeOrWake} does, decided from two facts so the rule is testable on its own. */
    enum Outcome { LAND, WAKE_AND_DEFER, SPAWN }

    /**
     * A loaded deck wins; a known-but-asleep train is woken, never re-spawned; only a dimension
     * with no train at all gets a seed.
     */
    static Outcome decide(boolean deckLoaded, boolean trainKnown) {
        if (deckLoaded) return Outcome.LAND;
        return trainKnown ? Outcome.WAKE_AND_DEFER : Outcome.SPAWN;
    }

    /**
     * Which registered anchor to ask back first: the one nearest {@code preferred} (the last group
     * the players saw moving), else the highest — the front of the train, where a returning
     * player expects to be. Null for an empty registry.
     */
    @Nullable
    static Integer pickAnchorToWake(Set<Integer> anchors, @Nullable Integer preferred) {
        Integer best = null;
        for (int a : anchors) {
            if (best == null) { best = a; continue; }
            if (preferred == null) {
                if (a > best) best = a;
            } else if (Math.abs(a - preferred) < Math.abs(best - preferred)) {
                best = a;
            }
        }
        return best;
    }

    /**
     * Put the player on {@code target}'s train: land, wake-and-defer, or spawn-and-place per
     * {@link #decide}. {@code why} only labels the log lines.
     */
    private static void placeOrWake(
            ServerPlayer player, ServerLevel target, DungeonTrainWorldData data, String why) {
        PlayerJoinEvents.FlatbedTarget flat = PlayerJoinEvents.findFlatbedTarget(target, data);
        switch (decide(flat != null, TrainBootstrapEvents.trainKnownIn(target))) {
            case LAND -> landOn(player, target, data, why, flat);
            case WAKE_AND_DEFER -> wakeAndDefer(player, target, data, why);
            case SPAWN -> {
                TrainBootstrapEvents.ensureTrainSpawned(target, data);
                placeOnTrain(player, target, data, why);
            }
        }
    }

    /**
     * The whole train is asleep in Sable holding. Ask one group back, park the player at the
     * ground pose unhurt, and hand the landing to {@link DtpPlacementService}, which retries each
     * tick until the reloaded deck settles (and releases the player in place if it never does).
     * A player who was already invulnerable is left as they were.
     */
    private static void wakeAndDefer(
            ServerPlayer player, ServerLevel target, DungeonTrainWorldData data, String why) {
        int woken = 0;
        for (UUID trainId : TrainBootstrapEvents.heldTrainIdsIn(target)) {
            Integer anchor = pickAnchorToWake(
                Trains.knownGroups(trainId).keySet(), TrainCarriageAppender.lastFixedAnchor(trainId));
            if (anchor == null) continue;
            if (TrainCarriageAppender.wakeHeldGroup(target, trainId, anchor, new HashSet<>())) woken++;
        }
        PlayerJoinEvents.SpawnPlacement sp = PlayerJoinEvents.computeBootstrapPlacement(
                target, data.dims(), data.getTrainY());
        LOGGER.info("[DungeonTrain] {}: train in {} is in Sable holding — woke {} group(s), holding {} at ({}, {}, {}) until a deck settles",
                why, target.dimension().location(), woken, player.getName().getString(),
                String.format("%.1f", sp.x()), sp.y(), String.format("%.1f", sp.z()));
        if (player.isPassenger()) player.stopRiding();
        player.teleportTo(target, sp.x(), sp.y(), sp.z(), sp.yaw(), sp.pitch());
        player.resetFallDistance();
        // DtpPlacementService clears this on landing or timeout, exactly as it does for /dtp.
        player.setInvulnerable(true);
        DtpPlacementService.enqueue(player, target, sp.x());
    }

    /** Teleport onto a settled deck, with the client-side deck hold that stops the free-fall race. */
    private static void landOn(
            ServerPlayer player, ServerLevel target, DungeonTrainWorldData data, String why,
            PlayerJoinEvents.FlatbedTarget flat) {
        LOGGER.info("[DungeonTrain] {} placing {} on train in {} at ({}, {}, {})",
                why, player.getName().getString(), target.dimension().location(),
                String.format("%.1f", flat.x()), String.format("%.1f", flat.y()), String.format("%.1f", flat.z()));
        player.teleportTo(target, flat.x(), flat.y(), flat.z(), -90.0f, 0.0f);
        // Client-side deck hold — same free-fall-during-spawn-stall race as
        // login (the target dim may have just spawned a fresh train).
        DungeonTrainNet.sendTo(player, new SpawnDeckHoldPacket(
            data.getTrainY() + 1.0, SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS));
    }

    /**
     * Land the player ON the train (front flatbed deck) in {@code target},
     * or beside the track if no group has bound yet. Yaw -90 faces +X (travel
     * direction). {@code why} only labels the log line.
     */
    private static void placeOnTrain(
            ServerPlayer player, ServerLevel target, DungeonTrainWorldData data, String why) {
        PlayerJoinEvents.FlatbedTarget flat = PlayerJoinEvents.findFlatbedTarget(target, data);
        if (flat != null) {
            landOn(player, target, data, why, flat);
            return;
        }

        PlayerJoinEvents.SpawnPlacement sp = PlayerJoinEvents.computeBootstrapPlacement(
                target, data.dims(), data.getTrainY());
        LOGGER.info("[DungeonTrain] {} teleporting {} to {} (ground fallback) at pos=({}, {}, {}) yaw={} pitch={}",
                why, player.getName().getString(), target.dimension().location(),
                String.format("%.1f", sp.x()), sp.y(), String.format("%.1f", sp.z()),
                String.format("%.1f", sp.yaw()), String.format("%.1f", sp.pitch()));
        player.teleportTo(target, sp.x(), sp.y(), sp.z(), sp.yaw(), sp.pitch());
    }
}
