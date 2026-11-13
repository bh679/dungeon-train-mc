package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.compat.vista.TvGrid;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.mehvahdjukaar.vista.configs.CommonConfigs;
import net.minecraft.world.level.Level;

/**
 * Whether a placed TV may be a speaker: judged from the world, never from what ticked it.
 *
 * <p>Vista ticks a TV's block entity and hands it to the video source with its own idea of "playing".
 * That block entity can outlive its place in the world: a TV joined into a bigger screen keeps a block
 * entity on a block that is now a child, a block entity's cached state can lag the block it sits on, and a
 * stale video source still ticks either. So a TV counts only when the block it stands on is, right now,
 * the master of its screen (or a lone TV) with its power state on, with that very block entity in place,
 * and the block entity is not paused and has power — Vista's own {@code isPlaying} read off the world.
 * A child block, or a master whose block says off, is never a candidate, whatever it was passed.</p>
 */
final class LiveSpeakerCandidate {

    private LiveSpeakerCandidate() {}

    /**
     * Whether a TV may play. Pure.
     *
     * @param litMaster  the block the TV stands on is its screen's master (or a lone TV) and switched on
     * @param placed     the block entity is the one the world holds at that block (not a stale one)
     * @param paused     Vista's pause
     * @param needsPower Vista's "TVs need external power" setting
     * @param hasEnergy  Vista's energy flag, which only matters with {@code needsPower}
     */
    static boolean eligible(boolean litMaster, boolean placed, boolean paused, boolean needsPower, boolean hasEnergy) {
        return litMaster && placed && !paused && (!needsPower || hasEnergy);
    }

    /** {@link #eligible} for {@code tv} in its level, read from the world. */
    static boolean eligible(Level level, TVBlockEntity tv) {
        return eligible(TvGrid.isLitMaster(level, tv.getBlockPos()), level.getBlockEntity(tv.getBlockPos()) == tv,
            tv.isPaused(), CommonConfigs.doesTvNeedExternalPower(), tv.isHasEnergy());
    }
}
