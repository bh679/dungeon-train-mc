package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * What a severing sounds like: a sheet of glass going, with a beacon dying under it.
 *
 * <p>Played in <b>both</b> copies. The two corridors are in completely different parts of the world
 * — one riding the train, one at the world floor — and the player is in exactly one of them at the
 * moment it happens, with no way of knowing which from here.</p>
 *
 * <p><b>Sound only.</b> There is no visual. An earlier version left soul particles guttering at the
 * hole for a few seconds; it was cut deliberately, so the sever is heard rather than seen. That
 * matters more than it sounds: the hole is usually made by someone reaching through from the near
 * side, who may not be looking at the cell they just broke — and a corridor is dark. A sound reaches
 * them wherever they are facing, and reaches the other copy too.</p>
 */
public final class PortalSeverEffects {

    /** The shatter. Pitched down from a pane of glass to something heavier. */
    private static final float GLASS_VOLUME = 1.4f;
    private static final float GLASS_PITCH = 0.65f;

    /**
     * A beacon switching off, laid under the shatter.
     *
     * <p>The glass alone says <i>damaged</i>. The falling pair of tones underneath it is what makes
     * the moment read as <i>lost</i> — which is the accurate description, since the corridor is not
     * broken so much as permanently one-way from here on. Quieter than the glass so it reads as the
     * tail of the break rather than a second event.</p>
     */
    private static final float BEACON_VOLUME = 0.8f;
    private static final float BEACON_PITCH = 1.0f;

    private PortalSeverEffects() {}

    /** Sound the break in both copies. */
    public static void play(ServerLevel level, BlockPos carriagePos, BlockPos twinPos) {
        playBreak(level, carriagePos);
        playBreak(level, twinPos);
    }

    /**
     * The sever, in two layers on the same tick: the shatter, and the beacon falling away under it.
     *
     * <p>Passed a {@code null} player so it goes to everyone in earshot rather than to whoever swung
     * the pickaxe — the other copy may hold someone who did not.</p>
     */
    private static void playBreak(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS,
            GLASS_VOLUME, GLASS_PITCH);
        level.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS,
            BEACON_VOLUME, BEACON_PITCH);
    }
}
