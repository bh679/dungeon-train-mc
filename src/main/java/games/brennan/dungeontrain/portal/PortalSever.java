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
 * Breaking a hole in a portal corridor's outer shell, past the point where the swap happens, kills
 * the way in.
 *
 * <p><b>What the hole actually does.</b> The illusion rests on the two corridors being identical and
 * sealed — inside one there is no external reference that could contradict the claim that you are in
 * the other. A hole in the shell is exactly such a reference, and only in the half past the midpoint:
 * there the same hole shows open sky beside a moving carriage on the train side, and the pocket
 * room's deepslate plug in the twin. {@link PortalEditMirror} copies the break faithfully to both
 * copies, which is what puts the contradiction in front of the player rather than hiding it. So the
 * portal stops pretending.</p>
 *
 * <p><b>Both ends of the pair, one direction each.</b> A hole in either corridor closes entry
 * through <i>both</i> — a portal is a pair sharing one room, and it is the pair that is broken.
 * What is never cut is the way out: twin → carriage keeps working forever, at both ends, severed or
 * not. A player standing in the pocket room when someone breaks the shell — or who breaks it
 * themselves from inside — can always walk back onto the train, through whichever corridor is
 * nearer. Nothing this class does can strand anybody, which is also why the trigger is restricted
 * to the carriage-side copy: a break in a twin corridor or anywhere in the room is ignored
 * outright.</p>
 *
 * <p><b>And the group opens up.</b> A severed pair is still three carriages of train, and with no
 * swap left in it the entry corridor is a dead end — in through the near door, and nowhere to go but
 * back. So the two blocks between the pair's two dummy doors are removed and the group becomes an
 * ordinary walk-through: entry corridor, through both doors, out of the exit corridor. That is the
 * backup for a portal that has stopped working, and {@link PortalCentreWall} holds the geometry and
 * the reasons. It happens twice over — here, for whoever is standing in the corridor at the moment it
 * breaks, and again in {@link PortalCarriageBuilder#stampMiddle} every time the group is re-stamped.</p>
 *
 * <p><b>Permanent.</b> Recorded against the carriage index in {@link PortalRegistry}, which is a
 * fixed place along the track, and persisted. It has to be stored rather than re-derived from the
 * hole: a corridor's blocks are re-stamped from its template every time the rolling window brings
 * it round again, so the hole itself is gone within a minute of being made.</p>
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
     * @param local the corridor-local cell, as {@code PortalPairIndex.Entry.localOfPlot} returns it
     */
    public static boolean isSeveringBreak(PortalCarriageLayout layout, PortalCarriageRole role,
                                          int[] local, boolean becameAir) {
        if (!becameAir) return false;

        int dx = local[0], dy = local[1], dz = local[2];
        if (!layout.isShellCell(dx, dy, dz)) return false;

        // The cell's centre, not its corner: a corridor's midpoint sits deliberately off a block
        // boundary (PortalCarriageLayout.midX), and testing the corner would put the whole block
        // that straddles the line on the wrong side of it.
        return layout.isTwinSideLocalX(dx + 0.5, role);
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
     * corridor working or neither. So the partner goes with it.</p>
     */
    public static void onCarriageBlockChanged(ServerLevel level, PortalPairIndex.Entry entry,
                                              int[] local, BlockState newState, BlockPos twinPos) {
        PortalFrames frames = entry.frames();
        if (!isSeveringBreak(frames.layout(), frames.role(), local, newState.isAir())) return;

        int broken = entry.carriageIndex();
        int partner = PortalCarriageRole.partnerIndex(broken, DungeonTrainConfig.getGroupSize());

        // Read before writing either: if this pair is already severed — including from a hole made
        // in its OTHER corridor — the effects have played once already and must not play again.
        PortalRegistry registry = PortalRegistry.get(level);
        boolean already = registry.isSevered(broken) || registry.isSevered(partner);
        registry.sever(broken);
        registry.sever(partner);
        if (already) return;

        BlockPos carriagePos = BlockPos.containing(
            entry.carriageWorld().x + local[0] + 0.5,
            entry.carriageWorld().y + local[1] + 0.5,
            entry.carriageWorld().z + local[2] + 0.5);

        // Both copies: whoever is in the corridor should hear it wherever they are standing, and the
        // two are in completely different parts of the world.
        PortalSeverEffects.play(level, carriagePos, twinPos);

        openCentreWall(level, entry);

        LOGGER.info("[DungeonTrain] Portal connection severed: carriages {} ({}) + {} (partner), "
                + "shell broken at local ({},{},{}) — world {}, twin {}. "
                + "Both ends are closed to entry; both ways out stay open.",
            broken, frames.role(), partner, local[0], local[1], local[2], carriagePos, twinPos);
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
     * True if this corridor no longer takes anyone in.
     *
     * <p>Asked only of a move whose destination is {@link PortalFrames#FRAME_TWIN}. A move back to
     * the carriage is never gated on this, by design.</p>
     */
    public static boolean isSevered(ServerLevel level, int carriageIndex) {
        return PortalRegistry.get(level).isSevered(carriageIndex);
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
     * @param toFrame  where the move is going: {@link PortalFrames#FRAME_TWIN} or
     *                 {@link PortalFrames#FRAME_CARRIAGE}
     * @param severed  whether this corridor's pair has been severed
     */
    public static boolean blocksMove(int toFrame, boolean severed) {
        return severed && toFrame == PortalFrames.FRAME_TWIN;
    }
}
