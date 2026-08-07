package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
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
 * <p><b>Severed one way only, and this is the important part.</b> Only carriage → twin is cut. The
 * return leg keeps working forever, for every corridor, severed or not. A player who is standing in
 * the pocket room when someone else breaks the shell — or who breaks it themselves from inside —
 * can always walk back onto the train. Nothing this class does can strand anybody, which is why the
 * trigger is restricted to the carriage-side copy: a break in the twin corridor or anywhere in the
 * room is ignored outright.</p>
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
     */
    public static void onCarriageBlockChanged(ServerLevel level, PortalPairIndex.Entry entry,
                                              int[] local, BlockState newState, BlockPos twinPos) {
        PortalFrames frames = entry.frames();
        if (!isSeveringBreak(frames.layout(), frames.role(), local, newState.isAir())) return;

        if (!PortalRegistry.get(level).sever(entry.carriageIndex())) return;

        BlockPos carriagePos = BlockPos.containing(
            entry.carriageWorld().x + local[0] + 0.5,
            entry.carriageWorld().y + local[1] + 0.5,
            entry.carriageWorld().z + local[2] + 0.5);

        // Both copies: whoever is in the corridor should hear and see it wherever they are standing,
        // and the two are in completely different parts of the world.
        PortalSeverEffects.play(level, carriagePos, twinPos);

        LOGGER.info("[DungeonTrain] Portal connection severed: carriage={} role={} shell broken at "
                + "local ({},{},{}) — world {}, twin {}. The way IN is closed; the way out stays open.",
            entry.carriageIndex(), frames.role(), local[0], local[1], local[2], carriagePos, twinPos);
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
}
