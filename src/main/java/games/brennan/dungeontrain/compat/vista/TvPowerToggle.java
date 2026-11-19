package games.brennan.dungeontrain.compat.vista;

import net.mehvahdjukaar.vista.common.tv.PowerState;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Makes a Vista TV a latch instead of a follower: a redstone pulse (button) flips it on or off and
 * a steady signal no longer matters, and a right-click flips it too (whatever is in hand) — the way a copper bulb
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
    /** The flags Vista writes a block's own power state with: clients and a re-render, no neighbour reactions. */
    private static final int VISTA_POWER_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE;

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

    /**
     * Server side: flip the screen the TV at {@code pos} belongs to (a right-click on any of its blocks).
     * Routes through Vista's own neighbour handler, which carries the flip across the grid.
     *
     * <p>Switching off first unpowers every other block of the grid that holds its own signal
     * ({@link #othersToClear}). Vista keeps a screen on while any block of it is {@code DIRECT} (the block a
     * signal or a click came in at); a click elsewhere switched that one block off and Vista's propagation
     * switched it straight back on, so only the block that had been clicked on could switch the screen off —
     * "not all segments take a click". Now any block does.</p>
     */
    public static void toggle(Level level, BlockPos pos) {
        if (level.isClientSide) return;
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(TVBlock.POWER_STATE) && state.getValue(TVBlock.POWER_STATE).isOn()) {
            Set<BlockPos> tiles = TvGrid.tiles(level, pos);
            for (BlockPos other : othersToClear(tiles, pos, p -> level.getBlockState(p).getValue(TVBlock.POWER_STATE) == PowerState.DIRECT)) {
                level.setBlock(other, level.getBlockState(other).setValue(TVBlock.POWER_STATE, PowerState.OFF), VISTA_POWER_FLAGS);
            }
        }
        FLIP.computeIfAbsent(level, l -> Collections.synchronizedSet(new HashSet<>())).add(GlobalPos.of(level.dimension(), pos.immutable()));
        level.neighborChanged(pos, Blocks.AIR, pos);
    }

    /** The blocks of a screen, other than the one being switched off, that hold their own signal and so must let go first. Pure. */
    static List<BlockPos> othersToClear(Set<BlockPos> tiles, BlockPos clicked, Predicate<BlockPos> holdsOwnSignal) {
        return tiles.stream().filter(p -> !p.equals(clicked)).filter(holdsOwnSignal).toList();
    }
}
