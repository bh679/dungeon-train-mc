package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalCarriageRole;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.portal.PortalEditMirror;
import games.brennan.dungeontrain.portal.PortalFrames;
import games.brennan.dungeontrain.portal.PortalCorridorEntities;
import games.brennan.dungeontrain.portal.PortalEntityTransit;
import games.brennan.dungeontrain.portal.PortalOccupants;
import games.brennan.dungeontrain.portal.PortalPairIndex;
import games.brennan.dungeontrain.portal.PortalPuppets;
import games.brennan.dungeontrain.portal.PortalRegistry;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the hallway portal for corridors that ride the train.
 *
 * <p>Unlike {@link PortalTransitEvents}, which shifts a player by a constant {@code deltaY} between
 * two stationary copies, the carriage copy moves. So the rule here is a mapping between frames
 * (see {@link PortalFrames}): crossing the midpoint outbound drops the player off the moving train
 * into a static twin corridor, and crossing back re-reads the carriage's position <i>at that
 * moment</i>, so they rejoin the train wherever it has since travelled to.</p>
 *
 * <p><b>Per carriage, not per sub-level.</b> One Sable sub-level holds a whole group —
 * {@code [BACK pad | groupSize carriages | FRONT pad]} — so a ship's AABB spans several carriages
 * and its minimum corner is a pad, not a corridor. Each portal carriage's origin is derived from
 * the group's anchor index and {@link CarriagePlacer#halfPadLen}.</p>
 *
 * <p><b>Origins are read as exact doubles, never floored.</b> The carriage frame comes straight from
 * the live ship AABB. That matters twice over: flooring to a block would make the mapping lurch a
 * whole block as the train rolls, and reading the origin live means the train's known positional
 * jitter cancels — the corridor's blocks and its origin move together, so the player's position
 * within the corridor stays correct however much the group is drifting.</p>
 *
 * <p><b>The twin is stamped on approach, not at the crossing.</b> It goes at the bottom of the world,
 * below the bedrock, in the carriage's <b>own chunk columns</b> — which is what makes it already
 * loaded, sent to the client and meshed by the time the swap happens ({@code ViewArea} sizes its
 * render grid to the full build height, so any Y in the same column qualifies). Doing it on approach
 * also keeps a few thousand block writes away from the instant the player crosses. See
 * {@link #TWIN_FLOOR_MARGIN} for why the depth is derived from the world floor rather than fixed.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalCarriageEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** All five axes relative: velocity and the render interpolation baseline both survive the move. */
    private static final Set<RelativeMovement> RELATIVE_ALL = EnumSet.allOf(RelativeMovement.class);

    /**
     * How far above the world floor a twin's own floor sits.
     *
     * <p>Twins go <b>below the bedrock</b>, at the very bottom of the world, rather than in the sky
     * above the train where they used to hang in plain view. Derived from
     * {@link ServerLevel#getMinBuildHeight()} rather than a fixed Y, so a world with a deeper floor
     * puts its twins deeper — they are always as low as that world allows.</p>
     *
     * <p>Nothing about the illusion depends on the height. The guarantee that a twin is already
     * loaded, sent and meshed when a player crosses comes from it sharing the carriage's <b>chunk
     * columns</b>, which constrains X and Z only: {@code ViewArea} sizes its render grid to the full
     * build height, so any Y in the same column is equally safe.</p>
     *
     * <p>One block of clearance keeps the structure's floor off the absolute limit, since blocks
     * cannot be placed below it.</p>
     */
    private static final int TWIN_FLOOR_MARGIN = 1;

    /** Distinct heights pairs are spread over, so two structures cannot land on each other. */
    private static final int TWIN_LANES = 6;

    /**
     * Vertical spacing between lanes — a corridor's full height plus the pocket room's, with room to
     * spare so no part of one structure reaches into the lane above.
     */
    private static final int TWIN_LANE_HEIGHT = 12;

    /**
     * Stamp the twin once a player is this close to the portal carriage — near enough to be about to
     * walk in. Deliberately tight: with a portal every few carriages, a generous range would keep
     * several twins alive at once and re-stamp each of them every time the train rolled on, which is
     * thousands of block writes a second for corridors nobody is walking into.
     */
    private static final double APPROACH_RANGE = 12.0;

    /**
     * Re-stamp the twin once the carriage has rolled this far from it. The twin has to stay inside
     * the chunks the client already has, or the swap would land the player in unloaded space — the
     * one thing this whole approach exists to avoid. 24 blocks keeps it within a chunk or two of the
     * carriage even at the smallest render distances.
     */
    private static final double TWIN_MAX_DRIFT = 24.0;

    /** Keep the twin this far below the build ceiling. */
    private static final int CEILING_MARGIN = 4;

    /**
     * ENTRY carriage index → world origin of that pair's structure. Carriage indices are global
     * along the track, so the entry index keys a pair on its own. In-memory only: the blocks are
     * re-stamped on the next approach anyway.
     */
    private static final Map<Integer, BlockPos> STRUCTURES = new HashMap<>();

    /**
     * How far outside the corridor's own cross-section the room extends, for the "is anyone in this
     * structure" test. The room is wider and taller than a corridor, and a player standing in it
     * must still pin the structure against being re-stamped.
     */
    private static final int POCKET_ROOM_SLACK = 8;

    /**
     * Ticks after a swap during which that player is left alone.
     *
     * <p>Belt to the hysteresis band's braces, and it covers a different cause: the band absorbs
     * positional disagreement, this absorbs the round trip. A teleport leaves the server ignoring
     * the client's movement until the acknowledgement arrives, so for a tick or two the position it
     * is judging is not one the client has agreed to yet.</p>
     */
    private static final int SWAP_COOLDOWN_TICKS = 20;

    /** Player → game time at which they may swap again. */
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

    private PortalCarriageEvents() {}

    private static boolean onCooldown(ServerPlayer player, long gameTime) {
        Long until = COOLDOWNS.get(player.getUUID());
        if (until == null) return false;
        if (gameTime >= until) {
            COOLDOWNS.remove(player.getUUID());
            return false;
        }
        return true;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;
        if (PortalRegistry.get(level).carriageEvery() <= PortalCarriageSelection.CARRIAGE_EVERY_OFF) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims);
        int groupSize = DungeonTrainConfig.getGroupSize();
        int padLen = CarriagePlacer.halfPadLen(dims);

        // Puppets are accumulated across every pair and sent once per player at the end of the tick.
        // Sending per pair would have a player near two of them receive two snapshots, each looking
        // like the whole picture, and the second would wipe the first.
        PortalPuppets.Session puppets = PortalPuppets.begin();

        for (UUID trainId : Trains.byTrainId(level).keySet()) {
            for (Map.Entry<Integer, ManagedShip> group : Trains.knownGroups(trainId).entrySet()) {
                int anchorPIdx = group.getKey();
                AABBdc bb = group.getValue().worldAABB();
                if (bb == null) continue;

                for (int slot = 0; slot < groupSize; slot++) {
                    int carriageIndex = anchorPIdx + slot;
                    if (!PortalCarriageSelection.isPortalCarriage(level, carriageIndex)) continue;

                    // Group layout: [BACK pad | carriage 0 | carriage 1 | … ]. Exact doubles, so the
                    // mapping neither lurches on a block boundary nor fights the group's jitter.
                    double originX = bb.minX() + padLen + (double) slot * dims.length();
                    double originY = bb.minY();
                    double originZ = bb.minZ();

                    int every = PortalRegistry.get(level).carriageEvery();
                    PortalCarriageRole role = PortalCarriageRole.roleFor(carriageIndex, every);
                    int pairKey = PortalCarriageRole.entryIndexOf(carriageIndex, every);

                    handlePortalCarriage(level, players, layout, dims, carriageIndex, role, pairKey,
                        group.getValue(), originX, originY, originZ, puppets);
                }
            }
        }

        puppets.dispatch(players);
    }

    private static void handlePortalCarriage(ServerLevel level, List<ServerPlayer> players,
                                             PortalCarriageLayout layout, CarriageDims dims,
                                             int carriageIndex, PortalCarriageRole role, int pairKey,
                                             ManagedShip ship,
                                             double originX, double originY, double originZ,
                                             PortalPuppets.Session puppets) {
        // One structure per pair, stamped from the ENTRY carriage's approach and keyed on its index,
        // so both carriages of a pair address the same room rather than building one each.
        BlockPos structure = STRUCTURES.get(pairKey);

        boolean occupied = structure != null && anyPlayerInStructure(players, layout, dims, structure)
            || anyPlayerInCorridor(players, layout, originX, originY, originZ);

        if (!occupied && !anyPlayerWithin(players, originX, originY, originZ, APPROACH_RANGE)) {
            // Nobody near this pair. Drop its puppets rather than leaving the last set standing in a
            // corridor the train has since rolled away from — and log the removals on the way out.
            PortalPuppets.forget(carriageIndex);
            return;
        }

        // Only the ENTRY carriage places the structure: it fixes where the room sits, and the EXIT
        // twin's position follows from it. An EXIT carriage approached first simply waits.
        if (structure == null && role != PortalCarriageRole.ENTRY) {
            PortalPuppets.forget(carriageIndex);
            return;
        }

        BlockPos structureOrigin = occupied && structure != null
            ? structure
            : ensureStructure(level, dims, pairKey, originX, originY, originZ);
        if (structureOrigin == null) {
            // No twin — a world too shallow to hold one. With only half a pair there is no opposite
            // corridor for a puppet to stand in.
            PortalPuppets.forget(carriageIndex);
            return;
        }

        // The entry twin sits at the structure's origin; the exit twin one corridor and one room along.
        BlockPos twinOrigin = role == PortalCarriageRole.ENTRY
            ? structureOrigin
            : structureOrigin.offset(PortalCarriageBuilder.exitTwinOffsetX(dims), 0, 0);

        PortalFrames frames = new PortalFrames(layout,
            new PortalFrames.Origin(originX, originY, originZ),
            new PortalFrames.Origin(twinOrigin.getX(), twinOrigin.getY(), twinOrigin.getZ()),
            role);

        // Publish for PortalEditMirror, which needs to answer "is this block in a portal corridor?"
        // on the hot path of every sub-level block change and cannot re-derive train geometry there —
        // and for PortalPuppetAttack, which needs the frames to measure a hit through the mirror.
        publishPairing(carriageIndex, ship, dims, originX, originY, originZ, twinOrigin, frames);

        for (ServerPlayer player : players) {
            if (player.isPassenger()) continue;
            if (onCooldown(player, level.getGameTime())) continue;

            double px = player.getX(), py = player.getY(), pz = player.getZ();
            PortalFrames.Move move = frames.requiredMove(px, py, pz);
            if (move == null) continue;

            // A player who was standing goes to the destination's floor surface rather than to the
            // carried-across local Y — the two frames' block grids differ by the ship's fractional
            // pose, and landing a fraction inside a twin that hangs in open air drops them through it.
            double targetY = player.onGround() ? frames.floorSurfaceY(move.toFrame()) : move.y();

            player.connection.teleport(move.x(), targetY, move.z(),
                player.getYRot(), player.getXRot(), RELATIVE_ALL);
            COOLDOWNS.put(player.getUUID(), level.getGameTime() + SWAP_COOLDOWN_TICKS);

            LOGGER.info("[DungeonTrain] Portal carriage swap: player={} carriage={} → {} ({}, {}, {}) → ({}, {}, {})",
                player.getName().getString(), carriageIndex,
                move.toFrame() == PortalFrames.FRAME_TWIN ? "TWIN" : "CARRIAGE",
                fmt(px), fmt(py), fmt(pz), fmt(move.x()), fmt(targetY), fmt(move.z()));
        }

        // Everything anywhere in the structure — both twin corridors and the pocket room between
        // them — is noted as being in a portal room, so vanilla's despawn rule leaves it alone. The
        // corridor scan below would miss the room, which is most of where things actually stand.
        protectStructureOccupants(level, dims, structureOrigin);

        // One scan of the corridors, shared by the two things that act on their occupants — so a mob
        // that transits is necessarily a mob that had a puppet, and neither can see an entity the
        // other missed.
        List<Entity> occupants = PortalCorridorEntities.inCorridors(level, frames);

        // Everything that is not a player crosses the midpoint on the same rule players do. Without
        // this a corridor is only half a portal: a villager followed in would stay behind on the
        // train, and a thrown ender pearl would land in the copy its thrower had just left.
        PortalEntityTransit.run(level, frames, occupants, carriageIndex);

        // Stand-ins for whoever is in the other copy, so two players either side of the midpoint can
        // still see each other. Last, so everything is described from where it ended up this tick
        // rather than where it was about to leave.
        PortalPuppets.gather(level, players, frames, ship, carriageIndex, occupants, puppets);
    }

    /**
     * The twin for this carriage, stamping it if there is none yet or the carriage has rolled out of
     * the chunk columns the old one sits in — which is the condition the crossing's seamlessness
     * depends on, so it is also exactly when a fresh one is worth the block writes.
     */
    private static BlockPos ensureStructure(ServerLevel level, CarriageDims dims, int pairKey,
                                            double originX, double originY, double originZ) {
        BlockPos existing = STRUCTURES.get(pairKey);

        // Same chunk columns as the carriage — that is what keeps the destination loaded — but at the
        // world floor rather than a fixed height above the train, and on a per-pair Y lane so two
        // pairs cannot stamp into each other.
        int twinY = twinFloorY(level, pairKey, originY);
        BlockPos wanted = BlockPos.containing(originX, twinY, originZ);

        // A world too shallow to hold the structure between its floor and the carriage gets no twin,
        // rather than one stamped through the train.
        int structureTop = twinY + Math.max(dims.height(), POCKET_ROOM_SLACK);
        if (structureTop >= originY || structureTop > level.getMaxBuildHeight() - CEILING_MARGIN) {
            return existing;
        }

        if (existing != null && horizontalDistance(existing, originX, originZ) <= TWIN_MAX_DRIFT) {
            return existing;
        }

        // Clear the outgoing structure rather than leaving it hanging in the sky. Without this the
        // train would trail abandoned corridors, a set every time a pair drifted out of range.
        if (existing != null) {
            carryStructureOccupants(level, dims, existing, wanted);
            PortalCarriageBuilder.eraseTwin(level, existing, dims);
        }

        PortalCarriageBuilder.stampPairStructure(level, wanted, dims);
        STRUCTURES.put(pairKey, wanted);
        LOGGER.info("[DungeonTrain] Stamped portal pair {} at {} (entry carriage at {}, {}, {})",
            pairKey, wanted, fmt(originX), fmt(originY), fmt(originZ));
        return wanted;
    }

    /**
     * Publish this carriage's corridor↔twin pairing for {@link PortalEditMirror}.
     *
     * <p>The carriage's blocks do not live at its world position — they are in its sub-level plot at
     * shipyard coordinates — so the world origin is converted with {@code worldToShip}, the same
     * conversion {@code CarriageBlockSnapshot} and {@code SoulCampfireHealEvents} use to reach
     * carriage blocks.</p>
     */
    private static void publishPairing(int carriageIndex, ManagedShip ship, CarriageDims dims,
                                       double originX, double originY, double originZ,
                                       BlockPos twinOrigin, PortalFrames frames) {
        if (!(ship instanceof SableManagedShip sable)) return;

        LevelPlot plot = sable.subLevel().getPlot();
        if (plot == null) return;

        // The world origin, not a precomputed plot origin: the entry converts each point through the
        // ship's own transform, so nothing here has to assume the plot's axes run the same way as the
        // world's — an assumption that reflected mirrored edits onto the opposite side of the corridor.
        PortalPairIndex.publish(carriageIndex,
            new PortalPairIndex.Entry(plot, ship, new Vec3(originX, originY, originZ), twinOrigin,
                dims, frames));
    }

    /** True if any player is anywhere inside a pair structure — either corridor, or the room between. */
    private static boolean anyPlayerInStructure(List<ServerPlayer> players, PortalCarriageLayout layout,
                                                CarriageDims dims, BlockPos structure) {
        AABB box = structureBox(dims, structure);
        for (ServerPlayer player : players) {
            if (box.contains(player.getX(), player.getY(), player.getZ())) return true;
        }
        return false;
    }

    /**
     * Note everything standing in the structure, so {@code PortalDespawnEvents} spares it.
     *
     * <p>The room is at the bottom of the world and the train rolls away from it, which to vanilla
     * reads as "nobody is near this mob" — so without this a villager led into the portal world is
     * quietly discarded while its player is away on the train.</p>
     */
    private static void protectStructureOccupants(ServerLevel level, CarriageDims dims,
                                                  BlockPos structure) {
        long gameTime = level.getGameTime();
        for (Entity entity : level.getEntities((Entity) null, structureBox(dims, structure), e -> true)) {
            PortalOccupants.protect(entity, gameTime);
        }
    }

    /** The whole pair structure as a box: both twin corridors and the room between them. */
    private static AABB structureBox(CarriageDims dims, BlockPos structure) {
        int span = PortalCarriageBuilder.exitTwinOffsetX(dims) + dims.length();
        return new AABB(
            structure.getX() - 1,
            structure.getY() - 1,
            structure.getZ() - POCKET_ROOM_SLACK,
            structure.getX() + span + 1,
            structure.getY() + dims.height() + POCKET_ROOM_SLACK,
            structure.getZ() + dims.width() + POCKET_ROOM_SLACK);
    }

    /**
     * Move whatever is standing in a structure along with it when it is restamped further down the
     * track.
     *
     * <p>The room is the same room — it is relocated to stay inside the carriage's chunk columns, not
     * replaced — so its occupants should arrive in it rather than be left behind. Without this, a
     * villager led into the portal world is stranded in mid-air at the world floor the moment the
     * player who led it there steps back onto the train and stops pinning the structure, and it falls
     * out of the world a second later. Now that everything transits, that is a routine sequence
     * rather than a curiosity.</p>
     *
     * <p>Players are never here: a structure holding one is never restamped in the first place. They
     * are skipped anyway, because moving a player wants the relative teleport the swap uses.</p>
     */
    private static void carryStructureOccupants(ServerLevel level, CarriageDims dims,
                                                BlockPos from, BlockPos to) {
        if (from.equals(to)) return;

        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();

        int carried = 0;
        for (Entity entity : level.getEntities((Entity) null, structureBox(dims, from), e -> true)) {
            if (entity instanceof ServerPlayer) continue;

            Vec3 velocity = entity.getDeltaMovement();
            entity.teleportTo(entity.getX() + dx, entity.getY() + dy, entity.getZ() + dz);
            entity.setDeltaMovement(velocity);
            carried++;
        }

        if (carried > 0) {
            LOGGER.info("[DungeonTrain] Portal structure moved {} → {}, carrying {} entities",
                from, to, carried);
        }
    }

    /**
     * The floor height for a pair's structure: the world floor, plus a per-pair lane.
     *
     * <p><b>Why lanes.</b> Every structure was stamped at the same height, at whatever X its entry
     * carriage happened to be at, with nothing checking whether another pair already occupied that
     * space. A structure is about 35 blocks long, and two pairs were observed stamping four blocks
     * apart — near-total overlap, each overwriting the other's corridor so neither matched its
     * carriage any more. Spreading pairs over {@link #TWIN_LANES} heights makes a collision need
     * both the same lane and overlapping X, which the lane count makes vanishingly rare.</p>
     *
     * <p>Lanes go in Y rather than Z deliberately: the whole loading guarantee is that a twin sits in
     * its carriage's <b>chunk columns</b>, and Y is the one axis that cannot take it out of them.</p>
     */
    private static int twinFloorY(ServerLevel level, int pairKey, double carriageY) {
        int floor = level.getMinBuildHeight() + TWIN_FLOOR_MARGIN;

        // Only as many lanes as actually fit between the world floor and the train. A world can be
        // shallow — this one runs its floor at Y 32 with the train at 78, which holds three lanes,
        // not six — and a lane stacked above the train is rejected by the fit check below, silently
        // leaving those pairs with no twin at all.
        int headroom = (int) carriageY - floor;
        int usableLanes = Math.max(1, Math.min(TWIN_LANES, headroom / TWIN_LANE_HEIGHT));

        return floor + Math.floorMod(pairKey, usableLanes) * TWIN_LANE_HEIGHT;
    }

    private static double horizontalDistance(BlockPos twin, double x, double z) {
        double dx = twin.getX() - x;
        double dz = twin.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** True if any player is inside the corridor whose local origin is at the given world position. */
    private static boolean anyPlayerInCorridor(List<ServerPlayer> players, PortalCarriageLayout layout,
                                               double originX, double originY, double originZ) {
        for (ServerPlayer player : players) {
            if (layout.insideCorridor(player.getX() - originX, player.getY() - originY,
                player.getZ() - originZ)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyPlayerWithin(List<ServerPlayer> players,
                                           double x, double y, double z, double range) {
        double r2 = range * range;
        for (ServerPlayer player : players) {
            if (player.distanceToSqr(x, y, z) <= r2) return true;
        }
        return false;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
