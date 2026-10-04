package games.brennan.dungeontrain.compat;

/**
 * "A broken container is spilling its contents right now." Entered and left around
 * {@code Containers.dropItemStack} by {@code mixin.ContainersSpillMixin} — the one funnel every vanilla
 * container spill goes through (chests, barrels, decorated pots, chiseled bookshelves, chest minecarts and
 * boats) — so the {@code EntityJoinLevelEvent} that fires inside it can tell a spill from a player's drop:
 * a disposable-camera photograph stashed in a container lands intact when the container breaks, instead of
 * igniting. Server thread only, synchronous, nestable.
 */
public final class ContainerSpill {

    private static int depth;

    private ContainerSpill() {}

    public static void begin() {
        depth++;
    }

    public static void end() {
        if (depth > 0) depth--;
    }

    /** True while a {@code Containers.dropItemStack} is on the stack. */
    public static boolean inProgress() {
        return depth > 0;
    }
}
