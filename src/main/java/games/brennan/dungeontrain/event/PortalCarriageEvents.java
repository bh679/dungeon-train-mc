package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalCarriageRevival;
import games.brennan.dungeontrain.portal.PortalCarriageRole;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorSize;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.portal.PortalEditMirror;
import games.brennan.dungeontrain.portal.PortalExitBindings;
import games.brennan.dungeontrain.portal.PortalExitTransit;
import games.brennan.dungeontrain.portal.PortalFrames;
import games.brennan.dungeontrain.portal.PortalCorridorEntities;
import games.brennan.dungeontrain.portal.PortalEntityTransit;
import games.brennan.dungeontrain.portal.PortalOccupants;
import games.brennan.dungeontrain.portal.PortalPairIndex;
import games.brennan.dungeontrain.portal.PortalPairResidency;
import games.brennan.dungeontrain.portal.PortalPuppets;
import games.brennan.dungeontrain.portal.PortalRegistry;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomMobs;
import games.brennan.dungeontrain.portal.PortalRoomTiler;
import games.brennan.dungeontrain.portal.PortalRoomTiling;
import games.brennan.dungeontrain.portal.PortalSever;
import games.brennan.dungeontrain.portal.PortalSwapDiagnostics;
import games.brennan.dungeontrain.portal.PortalStructure;
import games.brennan.dungeontrain.portal.PortalTripTracker;
import games.brennan.dungeontrain.portal.PortalTwinLanes;
import games.brennan.dungeontrain.net.PortalRoomFogPacket;
import games.brennan.dungeontrain.net.PortalSwapPacket;
import games.brennan.dungeontrain.net.PortalTrainAudioPacket;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.ShipAabbs;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.template.GateContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.WorldFloor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import javax.annotation.Nullable;
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
 * <p><b>The twin is stamped on approach, not at the crossing.</b> It goes in the empty basement a DT
 * overworld keeps <b>below its bedrock</b> — world no terrain reaches and no player can dig into —
 * in the carriage's <b>own chunk columns</b>, which is what makes it already loaded, sent to the
 * client and meshed by the time the swap happens ({@code ViewArea} sizes its render grid to the full
 * build height, so any Y in the same column qualifies). Doing it on approach also keeps a few
 * thousand block writes away from the instant the player crosses. See {@link PortalTwinLanes} for
 * the depth and the per-pair Y lanes, and {@link WorldFloor} for the basement itself.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalCarriageEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** All five axes relative: velocity and the render interpolation baseline both survive the move. */
    private static final Set<RelativeMovement> RELATIVE_ALL = EnumSet.allOf(RelativeMovement.class);

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
    private static final Map<Integer, PortalStructure> STRUCTURES = new HashMap<>();

    /**
     * How far outside the corridor's own cross-section the room extends, for the "is anyone in this
     * structure" test. The room is wider and taller than a corridor, and a player standing in it
     * must still pin the structure against being re-stamped.
     *
     * <p>A floor, not the figure: {@link #structureBox} takes the larger of this and what the
     * pair's actual room needs, so an authored room bigger than the built-in one widens the box
     * rather than falling outside it.</p>
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

    /**
     * Ticks between repeat warnings about one skipped group.
     *
     * <p>A group stays non-resident for a whole cull episode, so an ungated log would fire every
     * tick for every portal pair — the per-tick chatter {@code TrainCarriageAppender} collapses for
     * the same reason. 200 ≈ 10s: often enough to show an episode's shape in a test log, quiet
     * enough to read.</p>
     */
    private static final int SKIP_WARN_PERIOD_TICKS = 200;

    /** Group anchor pIdx → game time of the last skip warning logged for it. */
    private static final Map<Integer, Long> SKIP_WARNED_AT = new HashMap<>();

    /**
     * How far below a landing a supporting block may be and still count, in blocks.
     *
     * <p>A grounded player is placed on the destination's floor surface, so the support sits
     * exactly one block down and any depth would do. The margin is for the airborne case, where the
     * carried-across local Y can be most of a corridor's headroom above the floor. Deliberately
     * shallow: the question being asked is "is there a corridor here at all", and a stamped
     * corridor's floor is always within its own headroom, while the basement void has nothing for
     * hundreds of blocks.</p>
     */
    private static final int LANDING_SUPPORT_DEPTH = 4;

    /**
     * Pair keys somebody was near this tick, refilled from scratch each time.
     *
     * <p>Collected in the carriage loop, where the approach range is already being measured against
     * the carriage's live position, and read afterwards by {@link #tickRoomTiling} — which walks
     * structures rather than carriages and so has no way to work it out for itself. It is what lets
     * an endless room start building its copies while the player is still walking up to the corridor,
     * instead of filling in behind their back once they are inside.</p>
     */
    private static final Set<Integer> ACTIVE_PAIRS = new HashSet<>();

    /**
     * Pair keys whose corridors actually ran this tick, refilled from scratch each time.
     *
     * <p>A different question from {@link #ACTIVE_PAIRS}, which means "somebody is near". This one
     * means "this pair's group was reached and its swap plane exists", and the gap between the two is
     * the whole of {@link #tickStrandedPairs}: a player standing in a room is unquestionably near
     * their pair, and the pair can still be doing nothing at all because its carriage group has been
     * culled out from under them.</p>
     */
    private static final Set<Integer> LIVE_PAIRS = new HashSet<>();

    /**
     * How often somebody waiting on a revived carriage group is told so, in ticks — one second.
     *
     * <p>An action-bar line lingers on the client for a couple of seconds by itself, so this is about
     * keeping it up rather than about how fast it can be read.</p>
     */
    private static final int WAITING_MESSAGE_PERIOD_TICKS = 20;

    /** Said while the pair's carriage group is on its way back from holding. */
    private static final String WAITING_KEY_REVIVING = "chat.dungeontrain.portal.reviving";

    /** Said when it is neither resident nor in holding, so there is nothing to bring back. */
    private static final String WAITING_KEY_NO_TRAIN = "chat.dungeontrain.portal.no_train";

    /**
     * Player → the fog region they were last told about, so an unchanged one is not re-sent every
     * tick. Cleared for anyone who leaves a room, and pruned of players who leave the world.
     */
    private static final Map<UUID, PortalRoomFogPacket> LAST_FOG = new HashMap<>();

    /**
     * Player → the engine-audio region they were last told about, on the same "only when it changes"
     * rule as {@link #LAST_FOG}.
     */
    private static final Map<UUID, PortalTrainAudioPacket> LAST_TRAIN_AUDIO = new HashMap<>();

    /**
     * How far past a corridor mouth the train's engine takes to fade to silence, in blocks.
     *
     * <p>Short on purpose. The corridor is a copy of a carriage and sounds like one; the room is
     * somewhere else, and the walk between them is a few paces. A longer fade would have the engine
     * trailing a player around a room that is meant to read as off the train entirely.</p>
     */
    private static final float TRAIN_AUDIO_FADE_BLOCKS = 3.0f;

    private PortalCarriageEvents() {}

    /**
     * True when {@code (x, y, z)} is anywhere a portal pair owns — either corridor, the room between
     * them, any copy of that room currently standing, and the clearance a
     * {@link games.brennan.dungeontrain.portal.PortalRoomMode#BEDROCKLESS} room swept around itself.
     *
     * <p>Read by {@code PortalRoomSpawnGuard} to keep the dark from filling a portal room with
     * skeletons. The structures live here because this is what stamps and moves them, so the query
     * lives here too rather than the spawn rule keeping its own idea of where they are.</p>
     *
     * <p><b>Why the clearance counts.</b> In an ordinary world the swept space is basement void with
     * no floor in it, and nothing spawns on nothing. A Compatible Terrain world has no basement: the
     * twin is cut into rock, and the sweep leaves a wide unlit cavern with a solid floor right beside
     * the room — the textbook spawning volume this guard exists for, and one the ordinary structure
     * box stops well short of.</p>
     *
     * <p>Padded here rather than in {@link #structureBox}, which must not grow: that box is also the
     * occupancy, carry and despawn-protection volume, so widening it by the clearance would have a
     * pair adopting mobs fifty blocks away and dragging them along on every re-stamp.</p>
     */
    public static boolean isInsidePortalStructure(CarriageDims dims, double x, double y, double z) {
        if (STRUCTURES.isEmpty()) return false;
        for (PortalStructure structure : STRUCTURES.values()) {
            AABB box = structureBox(dims, structure);
            // Horizontally only, matching the sweep — see PortalRoomLayout#VOID_CLEARANCE for why the
            // clearance has no vertical term.
            int pad = structure.fogPad();
            if (pad > 0) box = box.inflate(pad, 0.0, pad);
            if (box.contains(x, y, z)) return true;
        }
        return false;
    }

    /**
     * The pair whose room <b>body</b> contains {@code (x, y, z)}, or {@code null} for anywhere else.
     *
     * <p>The room body is the structure minus its corridors: past the entry twin's far door, before
     * the exit twin's, and not inside any of the copies the endless modes scattered through the
     * tiling. Read by {@code BoardingProgressEvents} for the two things that must not count a
     * doorway as a room — the trip that credits the train's travel to somebody who has gone inside,
     * and the dwell behind "Train inside a train?".</p>
     *
     * <p><b>Why the corridor mask rather than an X range.</b> The pair's own exit can stand beside
     * another tile entirely and the copies stand anywhere, so the corridors are not one span either
     * side of the room. {@link PortalCarriageBuilder#allCorridorMask} is what <i>places</i> them, so
     * asking it cannot disagree about where they are — the same reason {@link #structureBox} reads
     * it instead of computing its own bounds.</p>
     *
     * <p><b>Unpadded, unlike {@link #isInsidePortalStructure}.</b> That query's {@code fogPad} is
     * spawning margin — deliberately generous, because a skeleton appearing just outside the room is
     * still the room's problem. Credit is not: the swept void beside a Compatible Terrain room is
     * not somewhere a player is riding the train from.</p>
     */
    @Nullable
    public static Integer portalRoomBodyPairKey(CarriageDims dims, double x, double y, double z) {
        if (STRUCTURES.isEmpty()) return null;
        for (Map.Entry<Integer, PortalStructure> entry : STRUCTURES.entrySet()) {
            PortalStructure structure = entry.getValue();
            if (!structureBox(dims, structure).contains(x, y, z)) continue;
            if (PortalCarriageBuilder.allCorridorMask(structure, dims)
                    .covers(Mth.floor(x), Mth.floor(y), Mth.floor(z))) {
                continue;
            }
            return entry.getKey();
        }
        return null;
    }

    private static boolean onCooldown(ServerPlayer player, long gameTime) {
        Long until = COOLDOWNS.get(player.getUUID());
        if (until == null) return false;
        if (gameTime >= until) {
            COOLDOWNS.remove(player.getUUID());
            return false;
        }
        return true;
    }

    /**
     * Note that a group was passed over because its pose could not be trusted.
     *
     * <p>Logged rather than swallowed because the skip is invisible from the outside: the pair
     * simply stops working for as long as the episode lasts, and without a line saying so a report
     * of "the portal did nothing" has no way to be told apart from one about a portal that was
     * never there. Throttled per anchor — see {@link #SKIP_WARN_PERIOD_TICKS}.</p>
     *
     * <p>Kept as its own throttle rather than folded into {@link PortalSwapDiagnostics} because the
     * subject is a group rather than a player, and a non-residency episode lasts far longer than a
     * player standing at a midpoint — hence the longer period. The reason text is shared so both
     * kinds of line read the same way in a log.</p>
     */
    private static void warnSkippedGroup(ServerLevel level, int anchorPIdx,
                                         PortalSwapDiagnostics.Reason reason) {
        long now = level.getGameTime();
        Long last = SKIP_WARNED_AT.get(anchorPIdx);
        if (last != null && now - last < SKIP_WARN_PERIOD_TICKS) return;
        SKIP_WARNED_AT.put(anchorPIdx, now);
        LOGGER.warn("[DungeonTrain] Portal swap refused [{}] for group anchorPIdx={}: {}. "
            + "There is no swap plane for it this tick — running one off a stale pose would freeze "
            + "the plane in world space.", reason.name(), anchorPIdx, reason.explanation());
    }

    /**
     * Whether a landing has anything under it to stand on.
     *
     * <p>Reads the world only where it is already loaded and never forces a chunk — a
     * {@code getChunk(FULL, true)} on the server tick is the shape of the Sable worldgen deadlock
     * (see {@code WorldgenForceGuard}), and an unloaded destination is one this should be refusing
     * anyway.</p>
     *
     * <p>Support only, with no headroom or suffocation test on purpose. The failure being guarded
     * is falling out of the world; adding a "is the destination clear" clause would start refusing
     * legitimate swaps into corridors that happen to have a trapdoor or a carpet where the player
     * lands, and trade a rare drop for a common dead portal.</p>
     */
    private static boolean landingSupported(ServerLevel level, double x, double y, double z) {
        for (int dy = 1; dy <= LANDING_SUPPORT_DEPTH; dy++) {
            BlockPos pos = BlockPos.containing(x, y - dy, z);
            if (!level.isLoaded(pos)) return false;
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Forget every structure when the server stops.
     *
     * <p>All three maps are static and would otherwise survive into the next world a single-player
     * client opens, where the same pair keys mean different places. That was survivable while a
     * structure was only a position — the next approach re-stamped it — but a stale record now also
     * claims room copies that were never built there, which {@code eraseTwin} would sweep at the
     * wrong coordinates.</p>
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STRUCTURES.clear();
        ACTIVE_PAIRS.clear();
        LAST_FOG.clear();
        LAST_TRAIN_AUDIO.clear();
        COOLDOWNS.clear();
        SKIP_WARNED_AT.clear();
        PortalSwapDiagnostics.clear();
        // Where each player left a room goes with the rooms themselves — a pair key means a different
        // place in the next world opened, so a surviving binding would name a corridor that is not
        // there.
        PortalExitBindings.clear();
        // Same reason again for both of these: a pair key names a different carriage in the next
        // world, so a surviving group handle would be about somebody else's train. Any force-load
        // ticket still outstanding — only possible for a player who quit standing in a room — is
        // released by the bootstrap sweep in TrainAssembler before the train re-fills.
        LIVE_PAIRS.clear();
        PortalPairResidency.clear();
        PortalCarriageRevival.clear();
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
        ACTIVE_PAIRS.clear();
        LIVE_PAIRS.clear();

        for (UUID trainId : Trains.byTrainId(level).keySet()) {
            for (Map.Entry<Integer, ManagedShip> group : Trains.knownGroups(trainId).entrySet()) {
                int anchorPIdx = group.getKey();
                ManagedShip ship = group.getValue();

                // Portal groups only, asked before the pose guards rather than per slot below —
                // not to save the work, which is a lottery draw, but because those guards WARN.
                // "This pair has no swap plane this tick" is only meaningful about a group that
                // would otherwise have had one, and most groups on a train are ordinary carriages
                // that never had a portal in them. Warning for those buries the signal the log
                // exists to carry. isPortalGroup answers for the whole group from any index in it.
                if (!PortalCarriageSelection.isPortalGroup(level, anchorPIdx)) continue;

                // knownGroups is a grow-only registry, so a handle in it can outlive its sub-level.
                // A stale one still answers worldAABB() with its LAST pose (ManagedShip#isResident
                // says so explicitly), and the carriage frame below is derived straight from that
                // box — so reading a dead handle freezes the swap plane in world space while the
                // train rolls on. An ordinary carriage then carries a player through the frozen
                // plane and the swap fires on somebody who never walked into a portal, which is
                // exactly the "dropped into the under-bedrock void" report. Every other reader of
                // this registry already gates the same way; see TrainFluidBarrier and
                // TrainCarriageAppender.
                if (!ship.isResident()) {
                    warnSkippedGroup(level, anchorPIdx,
                        PortalSwapDiagnostics.Reason.GROUP_NOT_RESIDENT);
                    continue;
                }
                // Covers null and the [0,0,0,0,0,0] box Sable hands back for a sub-level that has
                // not ticked yet: arithmetic on that lands the frame near the world origin.
                AABBdc bb = ship.worldAABB();
                if (ShipAabbs.isDegenerate(bb)) {
                    warnSkippedGroup(level, anchorPIdx, PortalSwapDiagnostics.Reason.DEGENERATE_AABB);
                    continue;
                }

                handleGroup(level, players, layout, dims, anchorPIdx, ship,
                    bb.minX(), bb.minY(), bb.minZ(), groupSize, padLen, puppets);
            }
        }

        // Pairs the walk above never reached, with somebody standing in their room. Before the
        // dispatch, so a pair revived or picked up here contributes its puppets to the same snapshot —
        // a second dispatch would look like the whole picture and wipe the first.
        tickStrandedPairs(level, players, layout, dims, groupSize, padLen, puppets);

        puppets.dispatch(players);

        // Once per pair rather than once per carriage, and outside the loop above so it also runs for
        // pairs nobody is near any more — those are exactly the ones that need draining.
        tickRoomTiling(level, dims, layout, players);
    }

    /**
     * Run every portal corridor in one carriage group off that group's live pose.
     *
     * <p>Shared by the two ways a group is reached — the walk over live trains, and
     * {@link #tickStrandedPairs} picking up a pair the walk missed — so a corridor's origin is
     * derived in exactly one place. Two callers deriving it separately is how a swap plane ends up
     * sitting a few blocks off the corridor a player is actually walking down.</p>
     *
     * <p>The group's box arrives as three doubles rather than whole: its minimum corner is the only
     * part a corridor origin is made of, and both callers have already had to look at the box to
     * reject a degenerate one.</p>
     *
     * @param minX the group's world AABB minimum corner, already checked for degeneracy by the caller
     */
    private static void handleGroup(ServerLevel level, List<ServerPlayer> players,
                                    PortalCarriageLayout layout, CarriageDims dims,
                                    int anchorPIdx, ManagedShip ship,
                                    double minX, double minY, double minZ,
                                    int groupSize, int padLen, PortalPuppets.Session puppets) {
        for (int slot = 0; slot < groupSize; slot++) {
            int carriageIndex = anchorPIdx + slot;
            if (!PortalCarriageSelection.isPortalCarriage(level, carriageIndex)) continue;

            // A portal is one group, so both its corridors key on that group's anchor and the role
            // falls out of which end of the group this one is.
            PortalCarriageRole role = PortalCarriageRole.roleFor(carriageIndex, groupSize);
            int pairKey = PortalCarriageRole.entryIndexOf(carriageIndex, groupSize);

            handlePortalCarriage(level, players, layout, dims, carriageIndex, role, pairKey,
                ship, corridorOriginX(minX, padLen, slot, dims, role), minY, minZ,
                groupSize, puppets);
        }
    }

    /**
     * Where one carriage's corridor starts, in world X.
     *
     * <p>Group layout: {@code [BACK pad | carriage 0 | carriage 1 | … ]}. Exact doubles, so the
     * mapping neither lurches on a block boundary nor fights the group's jitter.</p>
     *
     * <p>The corridor is longer than its slot and the EXIT one is pulled back into the cart
     * ({@link PortalCorridorSize}), so its frame origin is NOT its slot's. This has to match where
     * {@code CarriagePlacer} actually stamped the blocks, or the swap plane would sit a few blocks off
     * the corridor the player is walking down.</p>
     */
    private static double corridorOriginX(double groupMinX, int padLen, int slot, CarriageDims dims,
                                          PortalCarriageRole role) {
        return groupMinX + padLen + (double) slot * dims.length()
            + PortalCorridorSize.originOffsetX(role, dims);
    }

    /**
     * Give a pair whose group the walk never reached a way of working anyway, for as long as somebody
     * is inside its room.
     *
     * <p><b>What this is for.</b> Every swap — the base twin and every extra corridor an endless room
     * scatters through its tiles — runs off the walk over live carriage groups. A player eight rooms
     * out is nowhere near the train, so nothing holds the group in the simulation bubble; Sable culls
     * it, the walk skips it, and <i>every corridor in the room goes inert at once</i>. An endless room
     * repeats forever and is sealed, so there is nowhere else to walk: without this the only way out
     * is to die. {@link PortalPairResidency} stops it happening at all in the common case, and this is
     * what is left for the player who was already deep in the room when the group went.</p>
     *
     * <p>Three outcomes, and only the first does any work here. A group that is <b>resident but
     * missed</b> — a transient {@code findAll} dropout, or a registry entry the train grouping no
     * longer lists — is simply run, which is the same swap the walk would have done. A <b>culled</b>
     * one is asked back from holding and the player waits a moment in the corridor
     * ({@link PortalCarriageRevival} explains why nothing is teleported off a stale pose). One that is
     * neither is said out loud, because there is nothing else to be done about it.</p>
     */
    private static void tickStrandedPairs(ServerLevel level, List<ServerPlayer> players,
                                          PortalCarriageLayout layout, CarriageDims dims,
                                          int groupSize, int padLen, PortalPuppets.Session puppets) {
        if (STRUCTURES.isEmpty()) return;

        // Snapshot: handlePortalCarriage can relocate a structure, which replaces the entry.
        for (Map.Entry<Integer, PortalStructure> entry : new ArrayList<>(STRUCTURES.entrySet())) {
            int pairKey = entry.getKey();
            if (LIVE_PAIRS.contains(pairKey)) continue;
            // Only for somebody who is actually in there. A pair the train has simply rolled away
            // from is not stranding anybody, and reviving it would pin a sub-level for nothing.
            if (!anyPlayerInStructure(players, dims, entry.getValue())) continue;

            ManagedShip ship = PortalPairResidency.groupFor(pairKey);
            PortalCarriageRevival.Status status =
                PortalCarriageRevival.ensureLive(level, pairKey, ship);

            if (status == PortalCarriageRevival.Status.LIVE) {
                AABBdc bb = ship.worldAABB();
                if (ShipAabbs.isDegenerate(bb)) {
                    warnSkippedGroup(level, pairKey, PortalSwapDiagnostics.Reason.DEGENERATE_AABB);
                    continue;
                }
                handleGroup(level, players, layout, dims, pairKey, ship,
                    bb.minX(), bb.minY(), bb.minZ(), groupSize, padLen, puppets);
                continue;
            }

            boolean reviving = status == PortalCarriageRevival.Status.REVIVING;
            tellWaiting(level, players, dims, entry.getValue(), pairKey,
                reviving
                    ? PortalSwapDiagnostics.Reason.GROUP_REVIVING
                    : PortalSwapDiagnostics.Reason.PAIR_NOT_WALKED,
                reviving ? WAITING_KEY_REVIVING : WAITING_KEY_NO_TRAIN);
        }
    }

    /**
     * Tell whoever is in the room that its corridors are waiting on the train rather than broken.
     *
     * <p>On the action bar, because the alternative is a portal that silently does nothing — the exact
     * experience this whole pass exists to remove. Every {@link #WAITING_MESSAGE_PERIOD_TICKS} rather
     * than every tick: an action-bar line persists on the client for a couple of seconds on its own,
     * so re-sending it twenty times a second is a packet per player per tick for no visible
     * difference.</p>
     */
    private static void tellWaiting(ServerLevel level, List<ServerPlayer> players, CarriageDims dims,
                                    PortalStructure structure, int pairKey,
                                    PortalSwapDiagnostics.Reason reason, String messageKey) {
        if (PortalSwapDiagnostics.due(level, reason, pairKey)) {
            PortalSwapDiagnostics.refused(reason, "pair " + pairKey,
                "somebody is in this pair's room and no walk reached its carriage group");
        }

        if (level.getGameTime() % WAITING_MESSAGE_PERIOD_TICKS != 0) return;
        AABB box = structureBox(dims, structure);
        for (ServerPlayer player : players) {
            if (!box.contains(player.getX(), player.getY(), player.getZ())) continue;
            player.displayClientMessage(
                Component.translatable(messageKey).withStyle(ChatFormatting.GRAY), true);
        }
    }

    /**
     * Keep each structure's room copies up to date: one stamp or one erase per pair per tick.
     *
     * <p>Runs over {@link #STRUCTURES} rather than over portal carriages because a pair is one
     * structure however many of its carriages are in range, and because a structure whose carriages
     * have all rolled out of range still has copies to shed. A structure that is not drained cannot
     * be re-stamped ({@link PortalRoomTiler#drainedEnoughToRestamp}), so leaving one full would strand
     * its twin at a position the train has left.</p>
     */
    private static void tickRoomTiling(ServerLevel level, CarriageDims dims,
                                       PortalCarriageLayout layout, List<ServerPlayer> players) {
        if (STRUCTURES.isEmpty()) {
            clearFogFor(players, Set.of());
            clearTrainAudioFor(players, Set.of());
            PortalPairResidency.syncTo(level, Set.of());
            return;
        }

        // Snapshot: the tiler replaces entries as it works, and every candidate copy is tested
        // against the others so no two pairs stamp into each other.
        List<Map.Entry<Integer, PortalStructure>> pairs = new ArrayList<>(STRUCTURES.entrySet());
        Set<UUID> fogged = new HashSet<>();
        Set<UUID> inStructure = new HashSet<>();
        Set<Integer> occupiedPairs = new HashSet<>();

        for (Map.Entry<Integer, PortalStructure> pair : pairs) {
            PortalStructure structure = pair.getValue();
            List<PortalStructure> others = new ArrayList<>(pairs.size());
            for (Map.Entry<Integer, PortalStructure> other : pairs) {
                if (!other.getKey().equals(pair.getKey())) others.add(other.getValue());
            }

            // Copies start going up as soon as a player is near enough for the structure to have been
            // stamped at all, not once they walk in — so the room around them is already there when
            // they arrive rather than filling in behind their back. But only one ring of them until
            // somebody is actually inside: riding past a portal carriage is far more common than
            // going through it, and the full window is a hundred copies of a room nobody looked at.
            // Nobody near at all is the signal to drain.
            Set<PortalRoomTiling.Tile> standingIn = occupiedTiles(players, dims, layout, structure);
            // Somebody is in this room, so its carriage group must not be culled out from under
            // them — that is what turns every corridor in the room, copies included, into a dead
            // end with nowhere else to walk. Read here because the occupancy has just been measured
            // against the same box anyPlayerInStructure uses.
            if (!standingIn.isEmpty()) occupiedPairs.add(pair.getKey());
            int radius = PortalRoomTiling.MAX_RADIUS;
            if (standingIn.isEmpty() && ACTIVE_PAIRS.contains(pair.getKey())) {
                standingIn = Set.of(PortalRoomTiling.Tile.BASE);
                radius = PortalRoomTiling.APPROACH_RADIUS;
            }

            PortalStructure next =
                PortalRoomTiler.tick(level, dims, structure, standingIn, radius, others, pair.getKey());
            if (next != structure) STRUCTURES.put(pair.getKey(), next);

            sendFogFor(players, dims, layout, next, fogged);
            sendTrainAudioFor(players, dims, next, inStructure);
        }

        clearFogFor(players, fogged);
        clearTrainAudioFor(players, inStructure);
        // Take and release in one pass, so a ticket's lifetime is exactly a room's occupancy.
        PortalPairResidency.syncTo(level, occupiedPairs);
    }

    /**
     * Tell whoever is inside a structure where its corridors are, so the engine sound can follow them
     * through the corridor copy and fade out in the room.
     *
     * <p>Sent for <b>every</b> room mode, unlike the fog, which only the modes with an edge to hide
     * ask for. The sound rule is about the corridors and the walk out of them, which every portal
     * has.</p>
     */
    private static void sendTrainAudioFor(List<ServerPlayer> players, CarriageDims dims,
                                          PortalStructure structure, Set<UUID> inStructure) {
        AABB box = structureBox(dims, structure);
        BlockPos entry = structure.origin();
        BlockPos exit = structure.exitOrigin(dims);
        PortalTrainAudioPacket region = new PortalTrainAudioPacket(
            (int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
            (int) Math.ceil(box.maxX), (int) Math.ceil(box.maxY), (int) Math.ceil(box.maxZ),
            entry.getX(), entry.getY(), entry.getZ(),
            exit.getX(), exit.getY(), exit.getZ(),
            // The corridor's length, not the carriage's — the client fades the engine along the
            // corridor it is actually standing in.
            PortalCorridorSize.corridorLength(dims), dims.height(), dims.width(),
            TRAIN_AUDIO_FADE_BLOCKS);

        for (ServerPlayer player : players) {
            if (!box.contains(player.getX(), player.getY(), player.getZ())) continue;
            inStructure.add(player.getUUID());
            if (region.equals(LAST_TRAIN_AUDIO.get(player.getUUID()))) continue;
            LAST_TRAIN_AUDIO.put(player.getUUID(), region);
            PacketDistributor.sendToPlayer(player, region);
        }
    }

    /** Hand the engine back to its ordinary distance curve for anyone who has left a structure. */
    private static void clearTrainAudioFor(List<ServerPlayer> players, Set<UUID> stillInside) {
        if (LAST_TRAIN_AUDIO.isEmpty()) return;
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            if (stillInside.contains(id) || !LAST_TRAIN_AUDIO.containsKey(id)) continue;
            LAST_TRAIN_AUDIO.remove(id);
            PacketDistributor.sendToPlayer(player, PortalTrainAudioPacket.none());
        }
        // Same reasoning as the fog: somebody who left the world never gets the message, which is why
        // the client holds a region it can simply stop being inside.
        LAST_TRAIN_AUDIO.keySet().removeIf(id -> players.stream().noneMatch(p -> p.getUUID().equals(id)));
    }

    /**
     * Tell whoever is inside an endless room how far they can see, when that has changed.
     *
     * <p>The bounds sent are of the copies that have actually been stamped, not of the window the
     * mode asked for. A copy can fail to appear — an unloaded chunk, a spent budget, another pair's
     * structure in the way — and a fog reaching past the built edge would be describing a room the
     * player could walk out of.</p>
     *
     * <p><b>The test is against the padded region, not the structure.</b> A Bedrockless room's fog
     * reaches into the clearance it swept, and a player who steps off the structure into that void is
     * precisely who the fog is for — the ramp thickens it the further out they get. Testing the bare
     * {@link #structureBox} instead sent {@code none()} at the room's own wall, taking the fog away
     * at the exact step it should have started closing in. Same inflate as
     * {@link #isInsidePortalStructure}, and horizontal for the same reason: the clearance has no
     * vertical term.</p>
     */
    private static void sendFogFor(List<ServerPlayer> players, CarriageDims dims,
                                   PortalCarriageLayout layout, PortalStructure structure,
                                   Set<UUID> fogged) {
        if (!structure.mode().fogs()) return;

        // Bedrockless reaches past its own copies — there are none — into the clearance it swept, so
        // that mining out through the room's shell does not leave the fog behind while the player is
        // still standing in the void it was hiding. Every other mode pads by nothing and the bounds
        // stay what was stamped.
        int pad = structure.fogPad();
        AABB box = structureBox(dims, structure);
        if (pad > 0) box = box.inflate(pad, 0.0, pad);
        PortalRoomFogPacket region = new PortalRoomFogPacket(
            structure.tiledMinX(dims, layout) - pad,
            structure.origin().getY(),
            structure.tiledMinZ(dims, layout) - pad,
            structure.tiledMaxX(dims, layout) + pad,
            structure.origin().getY() + structure.roomSize().getY(),
            structure.tiledMaxZ(dims, layout) + pad,
            structure.fogRadius(),
            // The pad is also the ramp: the fog closes in over exactly the space that was swept, so
            // the walk into the void and the fog hiding its end are one distance.
            pad,
            structure.fogMinRadius());

        for (ServerPlayer player : players) {
            if (!box.contains(player.getX(), player.getY(), player.getZ())) continue;
            fogged.add(player.getUUID());
            if (region.equals(LAST_FOG.get(player.getUUID()))) continue;
            LAST_FOG.put(player.getUUID(), region);
            PacketDistributor.sendToPlayer(player, region);
        }
    }

    /** Take the fog back off anyone who was in a room this tick and is not any more. */
    private static void clearFogFor(List<ServerPlayer> players, Set<UUID> stillFogged) {
        if (LAST_FOG.isEmpty()) return;
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            if (stillFogged.contains(id) || !LAST_FOG.containsKey(id)) continue;
            LAST_FOG.remove(id);
            PacketDistributor.sendToPlayer(player, PortalRoomFogPacket.none());
        }
        // A player who left the world entirely never gets the message, which is exactly why the
        // client holds a region rather than a flag — it stops applying the moment they are not in it.
        LAST_FOG.keySet().removeIf(id -> players.stream().noneMatch(p -> p.getUUID().equals(id)));
        // Where each player left a room is pruned on the same pass and for the same reason: a
        // crash-disconnected player would otherwise leave a binding behind for the life of the
        // server, and a rejoining one starts from the corridor that is definitely there.
        PortalExitBindings.pruneTo(players.stream().map(ServerPlayer::getUUID).toList());
    }

    /**
     * Which room copies players are standing in, in the order they were found.
     *
     * <p>A player in one of the twin corridors resolves to whichever corridor-row copy that corridor
     * stands in — a real cell of the grid, since the corridor row tiles like any other. So walking
     * into a doorway is already enough to open the window, and it opens centred on where they are
     * rather than on the room they have not reached yet.</p>
     */
    private static Set<PortalRoomTiling.Tile> occupiedTiles(List<ServerPlayer> players,
                                                            CarriageDims dims,
                                                            PortalCarriageLayout layout,
                                                            PortalStructure structure) {
        AABB box = structureBox(dims, structure);
        Set<PortalRoomTiling.Tile> occupied = new LinkedHashSet<>();
        for (ServerPlayer player : players) {
            if (!box.contains(player.getX(), player.getY(), player.getZ())) continue;
            occupied.add(structure.tileAt(dims, layout, player.getX(), player.getZ()));
        }
        return occupied;
    }

    private static void handlePortalCarriage(ServerLevel level, List<ServerPlayer> players,
                                             PortalCarriageLayout layout, CarriageDims dims,
                                             int carriageIndex, PortalCarriageRole role, int pairKey,
                                             ManagedShip ship,
                                             double originX, double originY, double originZ,
                                             int groupSize, PortalPuppets.Session puppets) {
        // Which group this pair rides on, remembered before anything can return early — this is the
        // only place both facts are in hand, and it is what lets the group be found again once it has
        // been culled and dropped out of every train-side lookup. See PortalPairResidency.
        PortalPairResidency.note(pairKey, ship);

        // One structure per pair, stamped from the ENTRY carriage's approach and keyed on its index,
        // so both carriages of a pair address the same room rather than building one each.
        PortalStructure structure = STRUCTURES.get(pairKey);

        // Two different questions, deliberately not one.
        //
        // PINNED is "would relocating the structure move the ground out from under somebody" — which
        // only a player who is actually IN it can be, so it asks about the structure alone.
        //
        // NEARBY is "is this pair worth running at all", and a player in the carriage corridor counts
        // for that. It used to count for the other one too, and that was the bug: a player standing in
        // the corridor pinned the structure, so ensureStructure — and with it the TWIN_MAX_DRIFT check
        // — was never reached while they were in there. The train kept rolling; the twin stayed
        // stamped in the chunk columns the carriage occupied when they walked in; those columns fell
        // behind the train and unloaded; and then the swap was refused by the corridorLoaded guard in
        // swapPlayers. Walk straight in and it worked, dawdle and it silently did nothing.
        //
        // Relocating the twin under a player still on the TRAIN side is harmless — they have not
        // crossed, and both corridors are identical anyway — and it is precisely what keeps the
        // destination inside the columns the client already has.
        boolean pinned = structure != null && anyPlayerInStructure(players, dims, structure);
        boolean occupied = pinned
            || anyPlayerInCorridor(players, layout, originX, originY, originZ);

        if (!occupied && !anyPlayerWithin(players, originX, originY, originZ, APPROACH_RANGE)) {
            // Nobody near this pair. Drop its puppets rather than leaving the last set standing in a
            // corridor the train has since rolled away from — and log the removals on the way out.
            // Deliberately does not mark the pair active: an unvisited structure sheds its room
            // copies, which is what lets it be re-stamped when the train has rolled on.
            PortalPuppets.forget(carriageIndex);
            return;
        }
        ACTIVE_PAIRS.add(pairKey);

        // Only the ENTRY carriage places the structure: it fixes where the room sits, and the EXIT
        // twin's position follows from it. An EXIT carriage approached first simply waits.
        //
        // This is a rule about the ROLE, not about whether a structure happens to exist yet, and it
        // used to be written as the latter. The difference is the whole of this class's worst bug:
        // an EXIT carriage that fell through to ensureStructure measured the drift from ITS OWN
        // origin, which sits two carriage lengths ahead of the entry's (SLOT_ENTRY 0, SLOT_EXIT 2 —
        // 18 blocks at the default CarriageDims length, more on a longer one). That offset is
        // permanent, so most of TWIN_MAX_DRIFT was spent before the train had moved at all: the exit
        // relocated the whole structure onto its own coordinates a few blocks later, the entry
        // relocated it back, and the pair re-laid its room over and over at two overlapping spots.
        // Every re-lay carried the room's mobs to the new site and then spawned a fresh set on top of
        // them, and re-ran the stamp's shape cascade over blocks that were already standing — which
        // popped the room's pressure plates and left the drops floating. Anchored on the entry alone,
        // the drift check measures the one distance it was written for.
        if (structure == null && role != PortalCarriageRole.ENTRY) {
            // Only worth saying when somebody is actually in the corridor: being merely near an exit
            // whose entry has not been approached yet is the ordinary case every time a train rolls
            // past, and is not a failure of anything. Asked BEFORE the throttle, unlike the tests in
            // swapPlayers: this one decides whether there is anything to report at all, and asking
            // the throttle first would spend a window on a tick that was never going to log, leaving
            // a real occurrence a second later with nothing to say. The scan is affordable here
            // because handlePortalCarriage has already returned for any pair nobody is near.
            if (anyPlayerInCorridor(players, layout, originX, originY, originZ)
                && PortalSwapDiagnostics.due(level,
                    PortalSwapDiagnostics.Reason.EXIT_WITHOUT_STRUCTURE, carriageIndex)) {
                PortalSwapDiagnostics.refused(PortalSwapDiagnostics.Reason.EXIT_WITHOUT_STRUCTURE,
                    "carriage " + carriageIndex,
                    "pair=" + pairKey + " — this pair's entry corridor is carriage " + pairKey
                        + ", and nobody has been within " + (int) APPROACH_RANGE
                        + " blocks of it yet, so its room has never been placed");
            }
            PortalPuppets.forget(carriageIndex);
            return;
        }

        PortalStructure built = structure != null && (pinned || role != PortalCarriageRole.ENTRY)
            ? structure
            : ensureStructure(level, dims, pairKey, originX, originY, originZ, groupSize);
        if (built == null) {
            // No twin — a world too shallow to hold one. With only half a pair there is no opposite
            // corridor for a puppet to stand in.
            //
            // Said out loud, and gated on somebody actually being in the corridor, because this is a
            // corridor that IS a portal and does nothing at all: the fit test in ensureStructure is
            // against the room this pair rolled, so a taller room can fail here where the built-in one
            // fits, and from in-game the two are indistinguishable.
            if (anyPlayerInCorridor(players, layout, originX, originY, originZ)
                && PortalSwapDiagnostics.due(level,
                    PortalSwapDiagnostics.Reason.NO_TWIN_STRUCTURE, carriageIndex)) {
                int bedrockY = WorldFloor.bedrockY(level);
                PortalSwapDiagnostics.refused(PortalSwapDiagnostics.Reason.NO_TWIN_STRUCTURE,
                    "carriage " + carriageIndex,
                    "pair=" + pairKey + " lane=" + PortalTwinLanes.twinFloorY(
                        level.getMinBuildHeight(), bedrockY, pairKey, groupSize)
                        + " worldMinY=" + level.getMinBuildHeight() + " bedrockY=" + bedrockY
                        + " carriageY=" + fmt(originY)
                        + " — the pair's room does not fit between the basement floor and the train");
            }
            PortalPuppets.forget(carriageIndex);
            return;
        }

        // The entry twin sits at the structure's origin; the exit twin one corridor and one room
        // along — read off the structure, because the room's length is whatever this pair rolled.
        BlockPos twinOrigin = role == PortalCarriageRole.ENTRY
            ? built.origin()
            : built.exitOrigin(dims);

        PortalFrames frames = new PortalFrames(layout,
            new PortalFrames.Origin(originX, originY, originZ),
            new PortalFrames.Origin(twinOrigin.getX(), twinOrigin.getY(), twinOrigin.getZ()),
            role);

        // From here the pair has a swap plane, which is what LIVE_PAIRS means — noted before the
        // swap rather than after, because a tick in which nobody happened to cross is still a tick
        // this pair was working, and tickStrandedPairs must not go looking for a train to revive.
        LIVE_PAIRS.add(pairKey);

        // Publish for PortalEditMirror, which needs to answer "is this block in a portal corridor?"
        // on the hot path of every sub-level block change and cannot re-derive train geometry there —
        // and for PortalPuppetAttack, which needs the frames to measure a hit through the mirror.
        publishPairing(carriageIndex, ship, dims, originX, originY, originZ, twinOrigin, frames);

        swapPlayers(level, players, frames, carriageIndex, pairKey, built, dims, role,
            PortalRoomTiling.Tile.BASE, /*copyOnly*/ false);

        // Everything anywhere in the structure — both twin corridors and the pocket room between
        // them — is noted as being in a portal room, so vanilla's despawn rule leaves it alone. The
        // corridor scan below would miss the room, which is most of where things actually stand.
        protectStructureOccupants(level, dims, built);

        // One scan of the corridors, shared by the two things that act on their occupants — so a mob
        // that transits is necessarily a mob that had a puppet, and neither can see an entity the
        // other missed.
        List<Entity> occupants = PortalCorridorEntities.inCorridors(level, frames);

        // Everything that is not a player crosses the midpoint on the same rule players do. Without
        // this a corridor is only half a portal: a villager followed in would stay behind on the
        // train, and a thrown ender pearl would land in the copy its thrower had just left.
        //
        // The override is what keeps a led villager with its player: whoever is in this carriage's
        // corridor decides where things walking IN out of it end up, so a player bound to a copy
        // eight rooms out takes their followers there rather than leaving them at the original twin.
        PortalEntityTransit.run(level, frames, occupants, carriageIndex,
            PortalExitBindings.followerTwinFor(level, built, dims, pairKey, role,
                level.getGameTime()));

        // Stand-ins for whoever is in the other copy, so two players either side of the midpoint can
        // still see each other. Last, so everything is described from where it ended up this tick
        // rather than where it was about to leave.
        PortalPuppets.gather(level, players, frames, ship, carriageIndex, occupants, puppets);

        // The endless modes may have scattered further copies of this carriage's corridor through the
        // room (PortalRoomExits). Each is the same pairing at a different origin, so the same swap
        // runs again once per copy — outbound only, because walking in from the train always leads to
        // the original twin. Deliberately after the puppets: a copy has none, and gathering them per
        // copy would multiply that cost by however many are standing.
        for (PortalExitTransit.Copy copy : PortalExitTransit.framesFor(
                built, dims, layout, role, frames.carriage(), players)) {
            swapPlayers(level, players, copy.frames(), carriageIndex, pairKey, built, dims, role,
                copy.site().tile(), /*copyOnly*/ true);
            PortalEntityTransit.run(level, copy.frames(),
                inCopyOnly(copy.frames(), PortalCorridorEntities.inCorridors(level, copy.frames())),
                carriageIndex);
        }
    }

    /**
     * Move every player this pairing says is on the wrong side of its midpoint.
     *
     * <p>{@code copyOnly} is the guard {@link PortalExitTransit} exists to describe: an extra
     * corridor's frame covers the carriage as well, and a player standing on the train sits inside
     * every copy's frame at once. Without it they would be flung to a different copy every tick.</p>
     *
     * <p>{@code tile} is where this pairing's twin half stands — {@code BASE} for the original, the
     * copy's anchor for a copy. Coming out through it binds the player to it, so the way back in
     * leads where they came out; see {@link PortalExitBindings}.</p>
     */
    private static void swapPlayers(ServerLevel level, List<ServerPlayer> players,
                                    PortalFrames frames, int carriageIndex, int pairKey,
                                    PortalStructure structure, CarriageDims dims,
                                    PortalCarriageRole role, PortalRoomTiling.Tile tile,
                                    boolean copyOnly) {
        for (ServerPlayer player : players) {
            double px = player.getX(), py = player.getY(), pz = player.getZ();
            if (copyOnly && !PortalExitTransit.inCopy(frames, px, py, pz)) continue;

            // Walking IN goes to whichever copy this player last came out of, when that copy is
            // still standing. Resolved per player and re-checked every time, so a retired copy or a
            // relocated structure quietly falls back to the original twin rather than dropping
            // somebody into rock. Never applied to a copy's own frame: a copy is only a way out.
            PortalFrames.Origin boundTwin = copyOnly ? null : PortalExitBindings.twinFrameFor(
                level, structure, dims, player.getUUID(), pairKey, role);

            // Asked BEFORE the passenger and cooldown tests, which it did not used to be. Nothing
            // about the order changes what happens — all three merely skip the player — but it
            // changes what gets logged: every refusal below is now about somebody the rule actually
            // wanted to move, so a player merely walking past a corridor is silent, and a line in
            // the log always means a swap that should have fired and did not.
            PortalFrames.Move move = frames.redirectedTo(frames.requiredMove(px, py, pz), boundTwin);
            if (move == null) continue;

            // Every diagnostic below is wrapped in its own `due` test rather than handed a message to
            // throw away. See PortalSwapDiagnostics#due: it is what keeps these lines costing nothing
            // on the tick after the first one, which is what lets them stay switched on.
            UUID id = player.getUUID();

            if (player.isPassenger()) {
                if (PortalSwapDiagnostics.due(level, PortalSwapDiagnostics.Reason.PASSENGER, id)) {
                    PortalSwapDiagnostics.refused(PortalSwapDiagnostics.Reason.PASSENGER,
                        player.getName().getString(),
                        "carriage=" + carriageIndex
                            + " riding=" + player.getVehicle().getType().toShortString());
                }
                continue;
            }
            if (onCooldown(player, level.getGameTime())) {
                if (PortalSwapDiagnostics.due(level, PortalSwapDiagnostics.Reason.COOLDOWN, id)) {
                    PortalSwapDiagnostics.refused(PortalSwapDiagnostics.Reason.COOLDOWN,
                        player.getName().getString(),
                        "carriage=" + carriageIndex + " until=" + COOLDOWNS.get(id)
                            + " now=" + level.getGameTime());
                }
                continue;
            }

            // A corridor whose shell has been broken open past the midpoint no longer takes anyone
            // in. Only inbound: a move back to the carriage is never gated, so nobody who is already
            // in the room can be shut out of the train. See PortalSever.
            if (PortalSever.blocksMove(move.toFrame(),
                PortalSever.isSevered(level, carriageIndex))) {
                if (PortalSwapDiagnostics.due(level, PortalSwapDiagnostics.Reason.SEVERED, id)) {
                    PortalSwapDiagnostics.refused(PortalSwapDiagnostics.Reason.SEVERED,
                        player.getName().getString(),
                        "carriage=" + carriageIndex + " pair=" + pairKey
                            + " — the two blocks between the pair's doors are open instead, so the "
                            + "group can be walked straight through; '/dungeontrain portal severed "
                            + "clear' repairs it");
                }
                continue;
            }

            // Nor into a corridor the world has not got loaded. The original twin is safe by
            // construction — it sits in the carriage's own chunk columns — but a pair that stood its
            // exit beside another tile, or a player bound to a copy, can be sent somewhere a long way
            // off. Refusing means they walk through the carriage as though it were an ordinary one;
            // teleporting them anyway means dropping them through a floor that has not arrived.
            PortalFrames.Origin destination = boundTwin != null ? boundTwin : frames.twin();
            if (move.toFrame() == PortalFrames.FRAME_TWIN
                && !PortalExitBindings.corridorLoaded(level, destination, dims)) {
                if (PortalSwapDiagnostics.due(
                        level, PortalSwapDiagnostics.Reason.TWIN_NOT_LOADED, id)) {
                    PortalSwapDiagnostics.refused(PortalSwapDiagnostics.Reason.TWIN_NOT_LOADED,
                        player.getName().getString(),
                        "carriage=" + carriageIndex + " twin=(" + fmt(destination.x()) + ", "
                            + fmt(destination.y()) + ", " + fmt(destination.z()) + ")"
                            + (boundTwin != null ? " (bound copy)" : " (original)")
                            + " carriage now at (" + fmt(frames.carriage().x()) + ", "
                            + fmt(frames.carriage().y()) + ", " + fmt(frames.carriage().z()) + ")");
                }
                continue;
            }

            // A player who was standing goes to the destination's floor surface rather than to the
            // carried-across local Y — the two frames' block grids differ by the ship's fractional
            // pose, and landing a fraction inside a twin that hangs in open air drops them through it.
            double targetY = player.onGround()
                ? frames.floorSurfaceY(move.toFrame(), boundTwin)
                : move.y();

            // Last line of defence: never put anybody where there is nothing to stand on. The
            // guards above establish that the destination corridor is not severed and its chunks
            // are present, but "loaded" is not "stamped" — corridorLoaded only asks hasChunk, so a
            // loaded-but-empty destination sails through it and the player is dropped into the
            // basement void. Refusing turns that into the portal briefly doing nothing.
            //
            // TWIN only, and not because the carriage side is trusted: a carriage corridor's blocks
            // live in the ship's LevelPlot, not the world, so a world read at those coordinates
            // reports the empty air the train is passing through and would refuse every legitimate
            // way back onto the train. The twin is stamped into the world proper, so the read means
            // what it says there.
            if (move.toFrame() == PortalFrames.FRAME_TWIN
                && !landingSupported(level, move.x(), targetY, move.z())) {
                if (PortalSwapDiagnostics.due(level, PortalSwapDiagnostics.Reason.NO_LANDING, id)) {
                    PortalSwapDiagnostics.refused(PortalSwapDiagnostics.Reason.NO_LANDING,
                        player.getName().getString(),
                        "carriage=" + carriageIndex + " landing=(" + fmt(move.x()) + ", "
                            + fmt(targetY) + ", " + fmt(move.z()) + ") — chunks are loaded but empty "
                            + "for " + LANDING_SUPPORT_DEPTH
                            + " blocks down. Leaving them where they are.");
                }
                continue;
            }

            // Coming out: remember which copy of the room they left from. BASE clears the binding,
            // so a trip back through the original twin puts them at the start again — which is what
            // a player who deliberately walked back to it has asked for.
            if (move.toFrame() == PortalFrames.FRAME_CARRIAGE) {
                PortalExitBindings.bind(player.getUUID(), pairKey, tile);
                // How far this way out is from the way in — measured from where they are standing
                // NOW, before the teleport, because that is the exit corridor's position rather than
                // the carriage's. Empty for a player whose way in we never saw (logged in inside a
                // room, or joined after the trip began), which earns nothing rather than guessing.
                PortalTripTracker.noteExited(player.getUUID(), px, pz)
                    .ifPresent(metres -> AchievementEvents.notifyPortalExitDistance(player, metres));
            } else {
                // Going in: where they land is the way in every later exit is measured against.
                PortalTripTracker.noteEntered(player.getUUID(), pairKey, move.x(), move.z());
                // Going in: leave a trail, so whatever is following a second behind arrives where
                // this player did rather than at the original twin. A follower is by definition
                // behind, so by the time it crosses, this player is no longer here to be asked.
                PortalExitBindings.noteInbound(pairKey, role, player.getUUID(), level.getGameTime());
            }

            player.connection.teleport(move.x(), targetY, move.z(),
                player.getYRot(), player.getXRot(), RELATIVE_ALL);
            // Straight after the position, so the client's renderer knows this frame is the one to
            // finish its occlusion rebuild on. Without it the twin's sections — culled behind sealed
            // bedrock — are missing from the frame the player arrives in, and it flashes. See
            // client/portal/ClientPortalSwap.
            PacketDistributor.sendToPlayer(player, new PortalSwapPacket());
            COOLDOWNS.put(player.getUUID(), level.getGameTime() + SWAP_COOLDOWN_TICKS);

            LOGGER.info("[DungeonTrain] Portal carriage swap: player={} carriage={}{} → {} ({}, {}, {}) → ({}, {}, {})",
                player.getName().getString(), carriageIndex, copyOnly ? " (exit copy)" : "",
                move.toFrame() == PortalFrames.FRAME_TWIN ? "TWIN" : "CARRIAGE",
                fmt(px), fmt(py), fmt(pz), fmt(move.x()), fmt(targetY), fmt(move.z()));
        }
    }

    /**
     * Only the entities actually standing in the copy, never the carriage half its frame also covers.
     *
     * <p>The entity equivalent of {@code swapPlayers}' {@code copyOnly}: a villager riding the train
     * is inside every copy's frame, and left in the list it would be carried down into a corridor at
     * the bottom of the world.</p>
     */
    private static List<Entity> inCopyOnly(PortalFrames copy, List<Entity> occupants) {
        List<Entity> out = new ArrayList<>(occupants.size());
        for (Entity entity : occupants) {
            if (PortalExitTransit.inCopy(copy, entity.getX(), entity.getY(), entity.getZ())) {
                out.add(entity);
            }
        }
        return out;
    }

    /**
     * The twin for this carriage, stamping it if there is none yet or the carriage has rolled out of
     * the chunk columns the old one sits in — which is the condition the crossing's seamlessness
     * depends on, so it is also exactly when a fresh one is worth the block writes.
     */
    private static PortalStructure ensureStructure(ServerLevel level, CarriageDims dims, int pairKey,
                                                   double originX, double originY, double originZ,
                                                   int groupSize) {
        PortalStructure existing = STRUCTURES.get(pairKey);

        // Same chunk columns as the carriage — that is what keeps the destination loaded — but in
        // the basement under the world's bedrock rather than a fixed height above the train, and on
        // a per-pair Y lane so two pairs cannot stamp into each other.
        int worldMinY = level.getMinBuildHeight();
        int bedrockY = WorldFloor.bedrockY(level);
        int twinY = PortalTwinLanes.twinFloorY(worldMinY, bedrockY, pairKey, groupSize);
        BlockPos wanted = BlockPos.containing(originX, twinY, originZ);

        // Which room this pair rolls, and how big it is.
        //
        // A pair rolls its room ONCE, gated on where its entry carriage was when it first needed one:
        // the Diff-Level and dimension of that carriage decide which rooms (and which of their
        // sub-variants) are eligible, exactly as they do for a carriage or a track tile. A relocation
        // deliberately does NOT re-plan — the gate context travels with the train, so re-planning
        // further down the track could swap a player's room for a different one mid-visit. Relocating
        // keeps the room and the mode it was built with and only moves them.
        PortalStructure planned = existing != null
            ? existing.movedTo(wanted)
            : PortalCarriageBuilder.planStructure(level, dims, wanted, pairKey,
                GateContext.forCarriageAtWorldX(level, Mth.floor(originX), pairKey, dims.length()));

        // A world too shallow to hold the structure between its floor and the carriage gets no twin,
        // rather than one stamped through the train — or, in a world with a basement, one that would
        // push up through the bedrock into terrain a player could walk into.
        int structureHeight = Math.max(dims.height(), planned.roomSize().getY());
        int structureTop = twinY + structureHeight;
        if (structureTop >= originY
            || structureTop > level.getMaxBuildHeight() - CEILING_MARGIN
            || !PortalTwinLanes.fitsUnderWorld(worldMinY, bedrockY, twinY, structureHeight)) {
            return existing;
        }

        if (existing != null
            && horizontalDistance(existing.origin(), originX, originZ) <= TWIN_MAX_DRIFT) {
            return existing;
        }

        // Already standing exactly where the plan wants it. Nothing below would move a block, but it
        // would erase the room and lay an identical one back down in its place — a second placement
        // over the first, which is the shape of the bug this whole change is about. Cheap, and it
        // holds whatever a future caller decides to measure drift against.
        if (existing != null && existing.origin().equals(wanted)) {
            return existing;
        }

        // Far enough to want a new one, but not while copies of the room are still standing: the
        // erase below would have to sweep all of them in a single tick. The tiler is shedding them a
        // few per tick, faster than the train can travel the drift distance, so this waits rather
        // than pays. Correctness does not depend on it — eraseTwin covers the tiled bounds either
        // way — but a hitch would be felt.
        if (existing != null && !PortalRoomTiler.drainedEnoughToRestamp(existing)) {
            return existing;
        }

        // Clear the outgoing structure rather than leaving it hanging in the sky. Without this the
        // train would trail abandoned corridors, a set every time a pair drifted out of range.
        // Both the carry and the erase read the OLD record, so they cover exactly the box that was
        // written even if the room has since been authored at a different length.
        if (existing != null) {
            // The room's OWN mobs go with the room, before anything is carried: the stamp below rolls
            // and spawns a fresh set for the new site, and clearIntruders spares anything carrying
            // DT's contents tag — so a carried authored mob would simply stand next to its
            // replacement, once per relocation. What the carry is for is the villager or pet a player
            // led in, and that has no pair mark, so it is untouched by this.
            PortalRoomMobs.reapPair(level,
                PortalCarriageBuilder.footprintOf(level, existing, dims), pairKey);
            carryStructureOccupants(level, dims, existing, wanted);
            PortalCarriageBuilder.eraseTwin(level, existing, dims);
        }

        PortalCarriageBuilder.stampPairStructure(level, planned, dims, pairKey);
        STRUCTURES.put(pairKey, planned);
        // Where the exit stands, but only when it is not the ordinary place. A pair that moved it
        // (PortalRoomExits) is a portal a player has to search, and that is worth being able to see
        // in a log without walking the room — it is also the only outward sign the setting fired.
        String exit = PortalRoomTiling.Tile.BASE.equals(planned.exitTile())
            ? ""
            : ", exit moved to tile " + planned.exitTile().x() + "," + planned.exitTile().z();
        LOGGER.info("[DungeonTrain] Stamped portal pair {} at {} (room '{}' {} long{}, entry carriage at {}, {}, {})",
            pairKey, wanted, planned.roomName(), planned.roomLength(), exit,
            fmt(originX), fmt(originY), fmt(originZ));
        return planned;
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
            new PortalPairIndex.Entry(carriageIndex, plot, ship,
                new Vec3(originX, originY, originZ), twinOrigin, dims, frames));
    }

    /** True if any player is anywhere inside a pair structure — either corridor, or the room between. */
    private static boolean anyPlayerInStructure(List<ServerPlayer> players, CarriageDims dims,
                                                PortalStructure structure) {
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
                                                  PortalStructure structure) {
        long gameTime = level.getGameTime();
        for (Entity entity : level.getEntities((Entity) null, structureBox(dims, structure), e -> true)) {
            PortalOccupants.protect(entity, gameTime);
        }
    }

    /**
     * The whole pair structure as a box: both twin corridors, the room between them, and every copy
     * of that room currently standing.
     *
     * <p>Sized off the structure's own record. The X span is the room this pair actually rolled,
     * and the Z/Y slack is the larger of {@link #POCKET_ROOM_SLACK} and what that room needs — so
     * an authored room bigger than the built-in one still has its occupants pin the structure
     * against a re-stamp, be spared by the despawn rule, and be carried when it relocates.</p>
     *
     * <p><b>The tiled copies have to be inside it too.</b> This one box answers three questions —
     * is anyone in here (so do not re-stamp), what should the despawn rule spare, and what gets
     * carried when the structure relocates. A mob standing in an appended copy that fell outside the
     * box would be stranded in mid-air at the world floor the moment the structure moved. So the box
     * takes the union of the corridor span and the tiled rectangle; when nothing is tiled those are
     * the same thing and this is the box it always was.</p>
     *
     * <p><b>And so do the extra corridors.</b> One of those outlives the tile it is anchored to
     * ({@link games.brennan.dungeontrain.portal.PortalExitCopies}), so it can stand well outside the
     * tiled rectangle — and a player who walks into one that fell outside this box would not be
     * counted as inside the structure at all, which is what stops it being re-stamped out from under
     * them.</p>
     */
    private static AABB structureBox(CarriageDims dims, PortalStructure structure) {
        BlockPos origin = structure.origin();
        int span = structure.spanX(dims);
        Vec3i room = structure.roomSize();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims);
        // Half the room's overhang either side of the corridor, plus a block; never less than the
        // slack the built-in room was tuned with.
        int slackZ = Math.max(POCKET_ROOM_SLACK, (room.getZ() - dims.width()) / 2 + 1);
        int slackY = Math.max(POCKET_ROOM_SLACK, room.getY() - dims.height() + 1);

        int minX = Math.min(origin.getX() - 1, structure.tiledMinX(dims, layout) - 1);
        int maxX = Math.max(origin.getX() + span + 1, structure.tiledMaxX(dims, layout) + 2);
        int minZ = Math.min(origin.getZ() - slackZ, structure.tiledMinZ(dims, layout) - 1);
        int maxZ = Math.max(origin.getZ() + dims.width() + slackZ,
            structure.tiledMaxZ(dims, layout) + 2);

        // Read off the masks that place them, so this cannot disagree about where a corridor is —
        // the extra copies, and the pair's own exit when it stands beside another tile entirely.
        BoundingBox corridors = PortalCarriageBuilder.allCorridorMask(structure, dims).bounds();
        if (corridors != null) {
            minX = Math.min(minX, corridors.minX() - 1);
            maxX = Math.max(maxX, corridors.maxX() + 2);
            minZ = Math.min(minZ, corridors.minZ() - 1);
            maxZ = Math.max(maxZ, corridors.maxZ() + 2);
        }

        return new AABB(minX, origin.getY() - 1, minZ,
            maxX, origin.getY() + dims.height() + slackY, maxZ);
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
                                                PortalStructure from, BlockPos to) {
        BlockPos origin = from.origin();
        if (origin.equals(to)) return;

        int dx = to.getX() - origin.getX();
        int dy = to.getY() - origin.getY();
        int dz = to.getZ() - origin.getZ();

        int carried = 0;
        for (Entity entity : level.getEntities((Entity) null, structureBox(dims, from), e -> true)) {
            if (entity instanceof ServerPlayer) continue;
            // Loose items are not occupants — they are what a room's containers used to spill every
            // time one was erased. Carrying them meant a world's worth of them followed the rooms
            // around forever; the erase discards them instead (see PortalClear).
            if (PortalClear.isLoose(entity)) continue;

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

    // ─── Diagnosis ──────────────────────────────────────────────────────
    // Everything below exists to answer one question on the spot — "I walked in and nothing
    // happened, why?" — for /dungeontrain portal diagnose. It reads the same live state the tick
    // above acts on, and deliberately reads NOTHING of its own: a diagnosis derived from a second
    // copy of the geometry would agree with the code that is working and disagree with the code
    // that is not, which is the wrong way round.

    /**
     * A human-readable account of the nearest portal group's state, for
     * {@code /dungeontrain portal diagnose}.
     *
     * <p>Lives here rather than in the command because {@link #STRUCTURES} and {@link #COOLDOWNS} are
     * this class's private state and are most of the answer. The command formats and sends; this
     * decides what is true.</p>
     *
     * <p><b>The gate lines come first</b>, and are printed even when no group is found. "There is no
     * portal here" and "there is a portal here and it is broken" are the two answers most easily
     * confused, and only the gate arithmetic separates them — a carriage nearer the origin than
     * {@link PortalCarriageSelection#firstEligibleGroup()} never held a portal at all, so there is
     * nothing about it to fix.</p>
     */
    public static List<String> diagnose(ServerLevel level, ServerPlayer player) {
        List<String> out = new ArrayList<>();

        int groupSize = DungeonTrainConfig.getGroupSize();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims);
        PortalRegistry registry = PortalRegistry.get(level);
        int gate = PortalCarriageSelection.firstEligibleGroup();

        out.add("carriageEvery=" + registry.carriageEvery()
            + (registry.carriageEvery() <= PortalCarriageSelection.CARRIAGE_EVERY_OFF
                ? " (OFF — no carriage portals in this world)" : "")
            + ", groupSize=" + groupSize + ", dims=" + dims.length() + "x" + dims.width()
            + "x" + dims.height());
        out.add("gate: minLevel=" + PortalCarriageSelection.MIN_PORTAL_LEVEL
            + ", carriagesPerTier=" + DungeonTrainConfig.getCarriagesPerTier()
            + ", levelDelay=" + DungeonTrainConfig.getProgressionLevelDelay()
            + " → first eligible group ordinal " + gate
            + " (" + (long) gate * groupSize + " carriages from the origin)");

        Group nearest = nearestPortalGroup(level, player);
        if (nearest == null) {
            out.add("No portal group is loaded near you. If you expect one here, check the gate line "
                + "above: a group nearer the origin than ordinal " + gate + " never holds a portal.");
            return out;
        }

        int anchor = nearest.anchorPIdx();
        long ordinal = Math.floorDiv((long) anchor, Math.max(1, groupSize));
        out.add("nearest portal group: anchorPIdx=" + anchor + ", group ordinal " + ordinal
            + (Math.abs(ordinal) < gate ? " — BEFORE THE GATE, so this is not a portal" : " (past the gate)"));

        ManagedShip ship = nearest.ship();
        if (!ship.isResident()) {
            out.add("  sub-level: NOT RESIDENT — "
                + PortalSwapDiagnostics.Reason.GROUP_NOT_RESIDENT.explanation());
            return out;
        }
        AABBdc bb = ship.worldAABB();
        if (ShipAabbs.isDegenerate(bb)) {
            out.add("  sub-level: DEGENERATE AABB — "
                + PortalSwapDiagnostics.Reason.DEGENERATE_AABB.explanation());
            return out;
        }
        out.add("  sub-level: resident, AABB minX=" + fmt(bb.minX()) + " minY=" + fmt(bb.minY())
            + " minZ=" + fmt(bb.minZ()));

        int pairKey = PortalCarriageRole.entryIndexOf(anchor, groupSize);
        PortalStructure structure = STRUCTURES.get(pairKey);
        out.add("  pair " + pairKey + ": " + (registry.isSevered(pairKey)
            ? "SEVERED — the way in is closed; the two blocks between its doors are open so you can "
                + "walk the group through. '/dungeontrain portal severed clear' repairs it."
            : "not severed"));

        if (structure == null) {
            out.add("  structure: NONE placed yet — walk within " + (int) APPROACH_RANGE
                + " blocks of the ENTRY corridor (slot " + PortalCarriageSelection.SLOT_ENTRY
                + " of the group) to place it.");
        } else {
            out.add("  structure: room '" + structure.roomName() + "' at " + structure.origin()
                + ", mode=" + structure.mode());
        }

        int padLen = CarriagePlacer.halfPadLen(dims);
        for (int slot = 0; slot < groupSize; slot++) {
            int carriageIndex = anchor + slot;
            if (!PortalCarriageSelection.isPortalCarriage(level, carriageIndex)) continue;

            PortalCarriageRole role = PortalCarriageRole.roleFor(carriageIndex, groupSize);
            double originX = bb.minX() + padLen + (double) slot * dims.length()
                + PortalCorridorSize.originOffsetX(role, dims);
            double originY = bb.minY();
            double originZ = bb.minZ();

            out.add("  carriage " + carriageIndex + " (" + role + ", slot " + slot + "): origin ("
                + fmt(originX) + ", " + fmt(originY) + ", " + fmt(originZ) + ")");

            double localX = player.getX() - originX;
            boolean inside = layout.insideCorridor(localX, player.getY() - originY,
                player.getZ() - originZ);
            out.add("    you: " + (inside ? "INSIDE" : "outside") + " this corridor, localX="
                + fmt(localX) + " vs midpoint " + fmt(layout.midX()) + " ±"
                + fmt(PortalFrames.SWAP_HYSTERESIS) + " (swap fires past "
                + fmt(layout.midX() + PortalFrames.SWAP_HYSTERESIS) + " / before "
                + fmt(layout.midX() - PortalFrames.SWAP_HYSTERESIS) + ")");

            if (structure != null) {
                BlockPos twinOrigin = role == PortalCarriageRole.ENTRY
                    ? structure.origin()
                    : structure.exitOrigin(dims);
                PortalFrames.Origin twin = new PortalFrames.Origin(
                    twinOrigin.getX(), twinOrigin.getY(), twinOrigin.getZ());
                boolean loaded = PortalExitBindings.corridorLoaded(level, twin, dims);
                out.add("    twin: " + twinOrigin + ", chunks " + (loaded ? "LOADED" : "NOT LOADED — "
                        + PortalSwapDiagnostics.Reason.TWIN_NOT_LOADED.explanation())
                    + ", drift from this carriage "
                    + fmt(horizontalDistance(twinOrigin, originX, originZ))
                    + " (restamps past " + fmt(TWIN_MAX_DRIFT) + ")");
            }
        }

        if (player.isPassenger()) {
            out.add("  you are a PASSENGER — "
                + PortalSwapDiagnostics.Reason.PASSENGER.explanation());
        }
        Long until = COOLDOWNS.get(player.getUUID());
        if (until != null && level.getGameTime() < until) {
            out.add("  you are on swap COOLDOWN for another " + (until - level.getGameTime())
                + " ticks");
        }
        return out;
    }

    /** A group of the train, as the tick loop sees it. */
    private record Group(int anchorPIdx, ManagedShip ship) {}

    /**
     * The portal group whose last known position is nearest the player, or {@code null} if none is
     * loaded.
     *
     * <p>Measured against the ship's AABB centre rather than a corridor origin, so it still answers
     * for a group whose pose is stale — which is one of the states worth diagnosing, and one that a
     * corridor-origin search would simply not find.</p>
     */
    private static Group nearestPortalGroup(ServerLevel level, ServerPlayer player) {
        Group best = null;
        double bestDistance = Double.MAX_VALUE;

        for (UUID trainId : Trains.byTrainId(level).keySet()) {
            for (Map.Entry<Integer, ManagedShip> group : Trains.knownGroups(trainId).entrySet()) {
                int anchorPIdx = group.getKey();
                if (!PortalCarriageSelection.isPortalGroup(level, anchorPIdx)) continue;

                AABBdc bb = group.getValue().worldAABB();
                if (ShipAabbs.isDegenerate(bb)) {
                    // Still a candidate — a degenerate box is a diagnosis, not a disqualification —
                    // but only when nothing with a real position is in the running.
                    if (best == null) best = new Group(anchorPIdx, group.getValue());
                    continue;
                }

                double distance = player.distanceToSqr(
                    (bb.minX() + bb.maxX()) / 2.0,
                    (bb.minY() + bb.maxY()) / 2.0,
                    (bb.minZ() + bb.maxZ()) / 2.0);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new Group(anchorPIdx, group.getValue());
                }
            }
        }
        return best;
    }
}
