package games.brennan.dungeontrain.compat.vista;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Makes a Vista TV a latch instead of a follower: a redstone pulse (button) flips it on or off and
 * a steady signal no longer matters, and an empty-hand click flips it too — the way a copper bulb
 * behaves. Vista's block recomputes its power state from {@code level.hasNeighborSignal} on every
 * neighbour change; {@code mixin/vista/TVBlockMixin} redirects that read here, so Vista's own grid
 * propagation, sounds and screen merging keep running on the answer we give.
 *
 * <p>The only state is "was there a signal last time we looked", per TV, so a rising edge can be
 * told from any other neighbour change. The wanted on/off state is the block's own {@code powered}
 * property, which survives reloads; this map does not need to (a lever left on across a reload
 * just counts as one rising edge on the next neighbour change).</p>
 */
public final class TvPowerToggle {

    private static final Map<Level, Set<GlobalPos>> SIGNALLED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Level, Set<GlobalPos>> FLIP = Collections.synchronizedMap(new WeakHashMap<>());

    private TvPowerToggle() {}

    /**
     * What Vista should treat the signal as: {@code currentlyOn}, flipped on a rising edge of the
     * real signal or when a click asked for a flip.
     */
    public static boolean desiredSignal(Level level, BlockPos pos, boolean actualSignal, boolean currentlyOn) {
        GlobalPos key = GlobalPos.of(level.dimension(), pos.immutable());
        Set<GlobalPos> on = SIGNALLED.computeIfAbsent(level, l -> Collections.synchronizedSet(new HashSet<>()));
        boolean rising = actualSignal && !on.contains(key);
        if (actualSignal) on.add(key); else on.remove(key);
        Set<GlobalPos> flips = FLIP.get(level);
        boolean clicked = flips != null && flips.remove(key);
        return (rising || clicked) != currentlyOn;
    }

    /** Server side: flip this TV now (an empty-hand click). Routes through Vista's own neighbour handler. */
    public static void toggle(Level level, BlockPos pos) {
        if (level.isClientSide) return;
        FLIP.computeIfAbsent(level, l -> Collections.synchronizedSet(new HashSet<>())).add(GlobalPos.of(level.dimension(), pos.immutable()));
        level.neighborChanged(pos, Blocks.AIR, pos);
    }
}
