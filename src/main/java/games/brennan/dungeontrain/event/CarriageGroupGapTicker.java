package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.debug.DebugFlags;
import games.brennan.dungeontrain.net.CarriageGroupGapPacket;
import games.brennan.dungeontrain.net.CarriageNextSpawnPacket;
import games.brennan.dungeontrain.net.CarriageSpawnCollisionPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.train.CarriageGroupGap;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tick server pass that computes the X-axis gap between each carriage
 * group and the group with the next-higher pIdx in the same train, and
 * pushes a {@link CarriageGroupGapPacket} snapshot to every player in the
 * level. The client uses
 * {@link games.brennan.dungeontrain.client.CarriageGroupGapState} to expose
 * the data to {@link games.brennan.dungeontrain.client.VersionHudOverlay},
 * which renders the player's current-group gap as a second HUD line below
 * the carriage index.
 *
 * <p>Cadence: every {@link #BROADCAST_PERIOD_TICKS} level ticks. With three
 * dimensions all firing this subscriber the effective send rate is roughly
 * once per server tick — fine for HUD use, since the displayed gap shifts
 * by at most the per-tick velocity stride (~0.1 blocks at default speed).</p>
 *
 * <p>The actual AABB-touching math lives in
 * {@link CarriageGroupGap#compute(ServerLevel)} — this subscriber stays
 * clean of {@code org.joml.primitives.AABBdc} references because that
 * class is supplied via {@code additionalRuntimeClasspath} (not the
 * test-runtime classpath), and {@code @EventBusSubscriber} method-signature
 * verification at mod-construction time would otherwise fault under JUnit.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CarriageGroupGapTicker {

    private static final int BROADCAST_PERIOD_TICKS = 4;

    private static int tickCounter = 0;

    /**
     * Once-per-carriage chat-warning memory. A carriage's sub-level UUID
     * enters this set the first tick we ever see it colliding and stays
     * there for the rest of the server session — so each carriage
     * produces AT MOST ONE collision chat line in its lifetime, even if
     * the AABB intersection clears and re-occurs later. Cleared on
     * server stop / train wipe via {@link #resetWarnings} so a fresh
     * session re-warns from scratch.
     */
    private static final Set<UUID> WARNED_COLLIDING_SHIPS = ConcurrentHashMap.newKeySet();

    /** Wipe the once-per-carriage warning memory. Wired to server stop / train wipe. */
    public static void resetWarnings() {
        WARNED_COLLIDING_SHIPS.clear();
    }

    private CarriageGroupGapTicker() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (Math.floorMod(tickCounter++, BROADCAST_PERIOD_TICKS) != 0) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        // Post-spawn collision-check broadcast runs UNCONDITIONALLY — this
        // overlay is on by default so the player can SEE overlap bugs the
        // moment they happen, without flipping a debug toggle. During
        // testing we visualize EVERY carriage's 1×3×5 ship-space box, not
        // just the most-recent spawn — see
        // {@link TrainCarriageAppender#computeAllCarriageCollisionChecks}.
        // To revert to "most recent spawn only", swap back to
        // {@code TrainCarriageAppender.snapshotSpawnCollisionChecks().values()}.
        List<TrainCarriageAppender.SpawnCollisionCheck> collisionChecks =
            TrainCarriageAppender.computeAllCarriageCollisionChecks(level);
        List<CarriageSpawnCollisionPacket.Entry> collisionEntries =
            new ArrayList<>(collisionChecks.size());
        for (TrainCarriageAppender.SpawnCollisionCheck c : collisionChecks) {
            collisionEntries.add(new CarriageSpawnCollisionPacket.Entry(
                c.newShipId(),
                c.shipyardOrigin().getX(), c.shipyardOrigin().getY(), c.shipyardOrigin().getZ(),
                c.sizeX(), c.sizeY(), c.sizeZ(),
                c.colliding(),
                c.collidingPIdx()));

            // Once-per-carriage chat warning. A given carriage produces at
            // most one collision chat line for its entire lifetime — the
            // UUID stays in WARNED_COLLIDING_SHIPS even after the AABB
            // intersection clears, so transient overlaps don't re-fire.
            if (c.colliding() && WARNED_COLLIDING_SHIPS.add(c.newShipId())) {
                Component msg = Component.literal(
                    "[DungeonTrain] Collision detected: carriage pIdx="
                        + c.selfPIdx()
                        + " shipId=" + c.newShipId()
                        + " overlaps pIdx=" + c.collidingPIdx()
                        + " (ticks since spawn=" + c.ticksSinceSpawn() + ")"
                ).withStyle(ChatFormatting.RED);
                for (ServerPlayer player : players) {
                    player.sendSystemMessage(msg);
                }
            }
        }

        CarriageSpawnCollisionPacket collisionPacket = collisionEntries.isEmpty()
            ? CarriageSpawnCollisionPacket.empty()
            : new CarriageSpawnCollisionPacket(collisionEntries);
        for (ServerPlayer player : players) {
            DungeonTrainNet.sendTo(player, collisionPacket);
        }

        // Remaining wireframes (gap cubes, next-spawn preview) stay gated
        // behind the debug toggle — bandwidth + per-tick AABB scan only
        // matter when wireframes are actually being rendered.
        if (!DebugFlags.wireframesEnabled()) return;

        List<CarriageGroupGapPacket.Entry> entries = CarriageGroupGap.compute(level);
        CarriageGroupGapPacket packet = entries.isEmpty()
            ? CarriageGroupGapPacket.empty()
            : new CarriageGroupGapPacket(entries);

        // Planned-next-spawn snapshot for the wireframe-preview overlay.
        // Built from {@link TrainCarriageAppender}'s per-train cache so the
        // wireframe tracks the same placement math {@code spawnNewGroup} will
        // use when the J-keybind triggers a manual spawn.
        Map<UUID, TrainCarriageAppender.PlannedSpawn> planned = TrainCarriageAppender.snapshotPlannedSpawns();
        List<CarriageNextSpawnPacket.Entry> previewEntries = new ArrayList<>(planned.size());
        for (TrainCarriageAppender.PlannedSpawn p : planned.values()) {
            previewEntries.add(new CarriageNextSpawnPacket.Entry(
                p.referenceShipId(),
                p.worldOrigin().getX(), p.worldOrigin().getY(), p.worldOrigin().getZ(),
                p.sizeX(), p.sizeY(), p.sizeZ(),
                p.newAnchor()));
        }
        CarriageNextSpawnPacket previewPacket = previewEntries.isEmpty()
            ? CarriageNextSpawnPacket.empty()
            : new CarriageNextSpawnPacket(previewEntries);

        for (ServerPlayer player : players) {
            DungeonTrainNet.sendTo(player, packet);
            DungeonTrainNet.sendTo(player, previewPacket);
        }
    }
}
