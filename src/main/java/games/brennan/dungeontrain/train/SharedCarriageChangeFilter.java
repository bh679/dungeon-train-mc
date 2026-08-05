package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Set;

/**
 * The single answer to "does this block change alter what someone <em>built</em>?" for the shared
 * (drifting) carriage upload path. Called from
 * {@code SableBlockChangeGuardMixin.dungeontrain$trackSharedCarriageEdit} on every block change inside a
 * carriage sub-level; a {@code false} answer means the carriage is not dirtied and no delta is uploaded.
 *
 * <p>Two things are deliberately NOT build changes:</p>
 * <ol>
 *   <li><b>Breaking a loot container</b> — looting never fires the block-change hook at all, only
 *       breaking the block does, so this keeps ordinary looting from dirtying a carriage.</li>
 *   <li><b>A transient-property flip</b> — a pressure plate powering, a door swinging, a lamp lighting.
 *       These describe runtime state, not what was placed. Without this rule a player standing in a
 *       doorway generates a relay POST every ~1.5&nbsp;s: production carriage 32 recorded 127 changes of
 *       which 126 were one plate and one door (both part of the original template) flipping back and
 *       forth, pushing the carriage halfway to the relay's delta-log soft cap — past which the entire
 *       build is re-uploaded as a full re-baseline.</li>
 * </ol>
 *
 * <p>Consequence of rule 2, accepted on purpose: a door deliberately left open is not preserved when the
 * carriage is re-spawned in another world. Silencing {@code open} is what removes the bulk of the churn,
 * and "which way the door was swinging" is not a build.</p>
 */
public final class SharedCarriageChangeFilter {

    /**
     * Block-state property NAMES that describe runtime state rather than what was built. Matched by name
     * so the rule holds across every block using that name — that generality is the point.
     *
     * <p>{@code waterlogged} and {@code level} are deliberately absent: waterlogging a fence, or placing
     * a water source, IS a build edit and must still upload.</p>
     */
    private static final Set<String> TRANSIENT_PROPERTIES = Set.of(
            "powered",    // plates, buttons, levers, doors, trapdoors, fence gates, rails, note blocks
            "open",       // doors, trapdoors, fence gates, barrels
            "triggered",  // dispensers, droppers
            "attached",   // tripwire
            "disarmed",   // tripwire
            "lit",        // redstone lamps/ore/torch, furnaces, campfires
            "power",      // redstone wire, weighted pressure plates, daylight detectors
            "locked"      // repeaters
    );

    private SharedCarriageChangeFilter() {}

    /**
     * Whether {@code oldState → newState} should dirty the carriage it happened in. False for a broken
     * loot container and for a change that only flips {@link #TRANSIENT_PROPERTIES} on the same block.
     */
    public static boolean isBuildChange(BlockState oldState, BlockState newState) {
        if (newState.isAir() && isLootContainer(oldState)) return false;
        return !isTransientFlip(oldState, newState);
    }

    /**
     * True when both states are the same block and every property whose value differs is transient.
     * A different block is always a build change, however similar the two states look.
     */
    private static boolean isTransientFlip(BlockState oldState, BlockState newState) {
        if (!oldState.is(newState.getBlock())) return false;
        for (Property<?> property : oldState.getProperties()) {
            if (oldState.getValue(property).equals(newState.getValue(property))) continue;
            if (!TRANSIENT_PROPERTIES.contains(property.getName())) return false;
        }
        // Every differing property was transient — including the no-difference case (a redundant
        // setBlockState), which is likewise nothing to upload.
        return true;
    }

    private static boolean isLootContainer(BlockState state) {
        return ContainerContentsRoller.isContainerState(state)
                || ContainerContentsRoller.isDecoratedPot(state)
                || ContainerContentsRoller.isBrushable(state);
    }
}
