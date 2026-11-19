package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Breaking a hole in a portal corridor's side wall, roof or floor kills the way in.
 *
 * <p><b>What the hole actually does.</b> The illusion rests on the two corridors being identical and
 * sealed — inside one there is no external reference that could contradict the claim that you are in
 * the other. A hole in a side wall, the roof or the floor is exactly such a reference: it shows open
 * sky or track around a moving carriage on the train side, and the pocket room on the twin side. {@link PortalEditMirror} copies
 * the break faithfully to both copies, which is what puts the contradiction in front of the player
 * rather than hiding it. So the portal stops pretending. Any side-wall, roof or floor block counts,
 * in either half of the corridor, broken from either copy: from the train, or from the room by
 * somebody standing outside the twin corridor.</p>
 *
 * <p><b>Both ends of the pair, one direction each.</b> A hole in either corridor closes entry
 * through <i>both</i> — a portal is a pair sharing one room, and it is the pair that is broken.
 * What is never cut is the way out: twin → carriage keeps working forever, at both ends, severed or
 * not. A player standing in the pocket room when someone breaks the shell — or who breaks it
 * themselves from inside — stays where they are, and walking into either corridor still takes them
 * back onto the train. Nothing this class does can strand anybody, which is what lets a break on
 * the twin side sever as well.</p>
 *
 * <p><b>And the group opens up.</b> A severed pair is still three carriages of train, and with no
 * swap left in it the entry corridor is a dead end — in through the near door, and nowhere to go but
 * back. So the two blocks between the pair's two dummy doors are removed and the group becomes an
 * ordinary walk-through: entry corridor, through both doors, out of the exit corridor. That is the
 * backup for a portal that has stopped working, and {@link PortalCentreWall} holds the geometry and
 * the reasons. It happens twice over — here, for whoever is standing in the corridor at the moment it
 * breaks, and again in {@link PortalCarriageBuilder#stampMiddle} every time the group is re-stamped.</p>
 *
 * <p><b>Recorded against the pair, and only for as long as the damage lasts.</b> The verdict is
 * stored in {@link PortalRegistry} under the pair's key — its group's anchor — and persisted,
 * because it cannot be re-derived from the hole: a corridor's blocks are re-stamped from its
 * template every time the rolling window brings it round again, so the hole itself is gone within a
 * minute of being made. What the record must <i>not</i> do is outlive those blocks. It used to:
 * the pair stayed dead for the life of the world while a corridor stood there whole, which is how a
 * dimensional carriage could be found intact and leading nowhere, and how one could appear to have
 * spawned in already broken. {@code CarriagePlacer} now repairs the pair as it re-stamps the group
 * ({@link PortalRegistry#repairPair}) — the shell it is about to write is the answer to the
 * contradiction, so there is nothing left to refuse for.</p>
 */
public final class PortalSever {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalSever() {}

    /**
     * Whether a block change in a carriage-side corridor is the kind that severs it.
     *
     * <p>Split out from {@link #onCarriageBlockChanged} with no Minecraft level in sight so the rule
     * itself can be unit-tested — the geometry is where the subtlety is, not the plumbing.</p>
     *
     * <p>Side walls, roof and floor, along the whole corridor. The floor includes the crossing
     * lanterns: between the baffles they ARE the floor, so a hole through one is a hole to the
     * outside like any other. The end planes, the baffles and the doorways are left alone — breaking
     * one of those opens nothing to the outside.</p>
     *
     * @param local the corridor-local cell, as {@code PortalPairIndex.Entry.localOfPlot} or
     *              {@code localOfTwin} returns it
     */
    public static boolean isSeveringBreak(PortalCarriageLayout layout, int[] local, boolean becameAir) {
        if (!becameAir) return false;
        int dy = local[1], dz = local[2];
        return dz < layout.interiorMinZ() || dz > layout.interiorMaxZ()
            || dy == layout.floorY() || dy == layout.ceilingY();
    }

    /**
     * A block changed in a carriage-side corridor: sever the pair if the change was a hole in the
     * shell past the midpoint.
     *
     * <p>Called from {@link PortalEditMirror}, which is the single feed for carriage-side block
     * changes and has already resolved the position to a corridor and a local cell. Being on that
     * path means this also catches a shell blown out by a creeper or dismantled by a piston, not
     * only one a player mined — the illusion does not care what made the hole.</p>
     *
     * <p><b>One hole closes both ends.</b> A portal is a pair — {@code ENTRY | cart | EXIT} sharing
     * one room — and it is the <i>pair</i> that is broken, not the corridor that happened to be
     * mined. Severing only the corridor with the hole in it would leave the other end still taking
     * people into a room whose way back is now half dead, which is a worse state than either
     * corridor working or neither. So the record is made against the pair's own key and there is no
     * second entry to fall out of step with.</p>
     */
    public static void onCarriageBlockChanged(ServerLevel level, PortalPairIndex.Entry entry,
                                              int[] local, BlockState newState, BlockPos twinPos) {
        if (!isSeveringBreak(entry.frames().layout(), local, newState.isAir())) return;
        sever(level, entry, local, twinPos, "carriage");
    }

    /**
     * A block changed in a twin corridor: sever the pair if it was a hole in a side wall.
     *
     * <p>Called from {@link PortalEditMirror#onTwinBlockChanged} before the change is mirrored into
     * the carriage, so the hole can be named at both positions. The one-way rule is what makes this
     * safe from the room side: the player who broke it is left standing in the room, and walking into
     * the corridor still takes them back to the train.</p>
     */
    public static void onTwinBlockChanged(ServerLevel level, PortalPairIndex.Entry entry,
                                          int[] local, BlockState newState, BlockPos twinPos) {
        if (!isSeveringBreak(entry.frames().layout(), local, newState.isAir())) return;
        sever(level, entry, local, twinPos, "twin");
    }

    private static void sever(ServerLevel level, PortalPairIndex.Entry entry, int[] local,
                              BlockPos twinPos, String side) {
        PortalFrames frames = entry.frames();
        int broken = entry.carriageIndex();
        int pairKey = PortalCarriageRole.entryIndexOf(broken, DungeonTrainConfig.getGroupSize());

        // One record for the pair, whichever of its corridors was mined. severPair answers false
        // when the pair was already severed — including from a hole made in its OTHER corridor —
        // which is what keeps the effects to once.
        if (!PortalRegistry.get(level).severPair(pairKey)) return;

        BlockPos carriagePos = BlockPos.containing(
            entry.carriageWorld().x + local[0] + 0.5,
            entry.carriageWorld().y + local[1] + 0.5,
            entry.carriageWorld().z + local[2] + 0.5);

        // Both copies: whoever is in the corridor should hear it wherever they are standing, and the
        // two are in completely different parts of the world.
        PortalSeverEffects.play(level, carriagePos, twinPos);

        openCentreWall(level, entry);

        LOGGER.info("[DungeonTrain] Portal connection severed: pair {} (broken at carriage {}, {}, "
                + "{} side), shell broken at local ({},{},{}) — world {}, twin {}. "
                + "Both ends are closed to entry; both ways out stay open. Repaired when the "
                + "rolling window re-stamps the group.",
            pairKey, broken, frames.role(), side, local[0], local[1], local[2], carriagePos, twinPos);
    }

    /**
     * Open the doorway column through the wall between the pair's two corridors, so the group becomes
     * an ordinary walk-through rather than three carriages of dead end. See {@link PortalCentreWall}.
     *
     * <p><b>Why here as well as at stamp time.</b> {@code PortalCarriageBuilder.stampMiddle} reads the
     * severed state and would open the wall on its own — but only the next time the rolling window
     * brings this group round again, which is a minute or more away and may never happen while the
     * player who just broke the shell is standing in the corridor looking at it. This does it now; the
     * stamp keeps it open afterwards.</p>
     *
     * <p>Written into the ship's plot at shipyard coordinates through {@link SilentBlockOps}, the same
     * path {@link PortalEditMirror} uses — poking the chunk directly changes the block server-side and
     * tells no client. {@code clearBlockSilent} rather than an air state so nothing is left behind.</p>
     *
     * <p>Nothing mirrors into the twin: these cells belong to the cart between the corridors, so
     * {@code localOfPlot} puts them outside every corridor and the mirror ignores them — which is
     * right, because the twin pair has no cart between its halves to open.</p>
     */
    private static void openCentreWall(ServerLevel level, PortalPairIndex.Entry entry) {
        openCentreWall(level, entry.ship(), entry.carriageWorld(), entry.dims(), entry.kind(),
            entry.frames().role());
    }

    /**
     * The same opening, for a caller that holds no pairing.
     *
     * <p>{@link PortalWalkThrough} reaches this for a corridor whose swap keeps being refused —
     * including one whose pair never got as far as a structure, and so was never published to
     * {@link PortalPairIndex}. The corridor's world origin and its ship are what the tick handler has
     * in hand, and they are all the conversion needs.</p>
     *
     * <p><b>Idempotent.</b> A cell that is already air is left alone, so re-asserting an open plate
     * every second — which is what keeps a mid-episode re-stamp from sealing it again — costs a read
     * per cell and no write. Written at shipyard coordinates through {@link SilentBlockOps}, which
     * goes through {@code level.setBlock} and so reaches clients; the cells sit outside the
     * corridor's own box, so {@link PortalEditMirror} neither mirrors nor severs on them.</p>
     *
     * @param carriageWorld the corridor's origin in world space — its minimum corner, read live
     */
    public static void openCentreWall(ServerLevel level, ManagedShip ship, Vec3 carriageWorld,
                                      CarriageDims dims, PortalCorridorKind kind,
                                      PortalCarriageRole role) {
        for (int[] cell : PortalCentreWall.doorwayCellsFromCorridor(dims, kind, role)) {
            BlockPos pos = PortalPairIndex.plotPosOf(ship, carriageWorld, cell);
            if (level.getBlockState(pos).isAir()) continue;
            SilentBlockOps.clearBlockSilent(level, pos);
        }
    }

    /**
     * True if this pair no longer takes anyone in — asked of the pair's key, so both of its
     * corridors answer alike.
     *
     * <p>Asked only of a move whose destination is {@link PortalFrames#FRAME_TWIN}. A move back to
     * the carriage is never gated on this, by design.</p>
     */
    public static boolean isSevered(ServerLevel level, int pairKey) {
        return PortalRegistry.get(level).isPairSevered(pairKey);
    }

    /**
     * Whether a severed pair refuses this move — the one-way rule, in one place.
     *
     * <p>Inbound only. Everything this class does is about closing the way <b>in</b>; the way out is
     * never cut, at either end, so a player or a led villager already in the room can always walk back
     * onto the train through whichever corridor is nearer — including one of the copies an endless
     * room scatters through its tiles, which are ways out and nothing else.</p>
     *
     * <p>Written as a predicate rather than left inline because it is asserted in two places — the
     * player swap in {@code PortalCarriageEvents} and {@link PortalEntityTransit} — and the
     * asymmetry is the kind that survives a refactor only if it is somewhere it can be tested. Both
     * callers pass the answer they already have rather than a level, so nothing here reads the
     * registry twice.</p>
     *
     * <p>Also asked of the other way a pair stops taking people in — the transient give-up
     * {@link PortalWalkThrough} decides after a run of refusals — because the one-way rule is the
     * same rule in both cases and belongs in one place. Severed or merely given up, the way out is
     * never cut.</p>
     *
     * @param toFrame       where the move is going: {@link PortalFrames#FRAME_TWIN} or
     *                      {@link PortalFrames#FRAME_CARRIAGE}
     * @param disconnected  whether this pair has stopped taking anyone in, severed or given up
     */
    public static boolean blocksMove(int toFrame, boolean disconnected) {
        return disconnected && toFrame == PortalFrames.FRAME_TWIN;
    }
}
