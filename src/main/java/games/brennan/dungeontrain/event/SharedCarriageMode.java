package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.cheat.RunIntegrity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Predicate;

/**
 * Which shared-carriage pool a world draws from and contributes to: {@link #FREE_PLAY} or
 * {@link #NORMAL}. The relay keeps the two apart, so a build made in a Free Play session can never
 * turn up as content in a legitimate run, or the reverse.
 *
 * <p>A world is Free Play if <b>any</b> player in it is running cheated
 * ({@link RunIntegrity#isCheated}) — creative/spectator, a known cheat mod, deviated Adventure Item
 * Stats settings, or a non-allowlisted command. "Any" rather than "the host" is the only reading
 * under which a Free Play player can never edit a normal-pool build: the world simply never holds
 * one. It is deliberately the conservative direction — a world is easy to taint and, because the
 * per-player mark is sticky, it does not untaint.</p>
 *
 * <p>Note this is evaluated live rather than cached: a player can flip mid-session by switching to
 * creative, and {@code SharedCarriageEvents} watches for exactly that.</p>
 */
public final class SharedCarriageMode {

    /** Pool identifiers as the relay stores them. */
    public static final String NORMAL = "normal";
    public static final String FREE_PLAY = "freeplay";

    private SharedCarriageMode() {}

    /** The pool {@code level} belongs to right now. An empty world is {@link #NORMAL}. */
    public static String current(ServerLevel level) {
        if (level == null) return NORMAL;
        return of(level.players(), RunIntegrity::isCheated);
    }

    /**
     * The rule itself, over any list and any "is this one cheated" predicate. Generic so it can be
     * exercised without a server — constructing a real {@link ServerPlayer} needs one, and the rule
     * never looks inside a player anyway.
     */
    public static <T> String of(List<T> players, Predicate<T> cheated) {
        if (players == null || players.isEmpty()) return NORMAL;
        for (T p : players) {
            if (p != null && cheated.test(p)) return FREE_PLAY;
        }
        return NORMAL;
    }
}
