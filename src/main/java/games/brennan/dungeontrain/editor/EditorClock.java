package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.PortalRoomSky;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * The Train Editor's clock: a stopped midday, except while an author stands in a dimensional
 * carriage plot whose Sky is {@link PortalRoomSky#CYCLE Day/Night}.
 *
 * <p>Plots are authored under one fixed light so what an author sees is the build and not the hour.
 * The one Sky that <em>is</em> about the hour — Day/Night, a room that darkens with the surface it
 * pretends to stand on — would be invisible under a stopped clock, so while somebody stands in such
 * a plot the clock runs, and fast: {@link #CYCLE_SPEED} clock ticks per game tick, a whole day in
 * two minutes, which is how long an author will actually stand there watching it. Step out and the
 * clock stops at noon again.</p>
 *
 * <p>The speed rides NeoForge's own variable day length ({@code ServerLevel#setDayTimePerTick}),
 * which the server syncs to every client on the ordinary time packet — there is no client side to
 * this. The other Skies need nothing from the clock: Daylight, Nether and End pin the lightmap
 * inside the room ({@code LightTexturePortalRoomMixin}), and outside every room the world is at
 * noon anyway.</p>
 *
 * <p><b>Per level, not per player.</b> The clock is the world's, so with two authors online the one
 * standing in a Day/Night plot sets the hour for both. An editor world is a single author's in
 * practice, and the alternative — a per-player spoofed clock — would be a second clock for every
 * system that reads the first.</p>
 *
 * <p>Every write below is guarded by a read, so a steady tick in either state is two lookups; the
 * two transitions force a time sync so the client snaps with the author's foot rather than up to a
 * second later.</p>
 */
public final class EditorClock {

    /** Clock ticks per game tick while an author stands under a Day/Night sky: a day in two minutes. */
    public static final float CYCLE_SPEED = 10.0f;

    /** NeoForge's "no speed set" sentinel — clock and game ticks coupled 1:1, as vanilla. */
    private static final float VANILLA_SPEED = -1.0f;

    private EditorClock() {}

    /**
     * Run or rest the clock for this tick.
     *
     * <p>Call once per level tick from the editor's per-player pass, after it has asked
     * {@link EditorPlotSky#update} for every player at the build area. Inert outside editor worlds.</p>
     *
     * @param cycleSeen true when at least one player stands in a plot whose Sky is Day/Night
     */
    public static void tick(ServerLevel level, boolean cycleSeen) {
        if (!EditorQuietRuleEvents.isEditorWorld(level)) return;
        if (cycleSeen) {
            runCycle(level);
        } else {
            restMidday(level);
        }
    }

    /** Let the clock run at {@link #CYCLE_SPEED}; a no-op once it already is. */
    private static void runCycle(ServerLevel level) {
        boolean changed = false;
        GameRules.BooleanValue daylight = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
        if (!daylight.get()) {
            daylight.set(true, level.getServer());
            changed = true;
        }
        if (level.getDayTimePerTick() != CYCLE_SPEED) {
            level.setDayTimePerTick(CYCLE_SPEED); // forces its own time sync when it changes
        } else if (changed) {
            level.getServer().forceTimeSynchronization();
        }
    }

    /**
     * Stop the clock at noon; a no-op once it already is.
     *
     * <p>Public because server start needs it too: an author who quit standing in a Day/Night plot
     * saved a running 10x clock into {@code level.dat}, and the world should come back at a stopped
     * noon rather than wherever the sun had got to.</p>
     */
    public static void restMidday(ServerLevel level) {
        boolean changed = false;
        GameRules.BooleanValue daylight = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
        if (daylight.get()) {
            daylight.set(false, level.getServer());
            changed = true;
        }
        if (level.getDayTimePerTick() != VANILLA_SPEED) {
            level.setDayTimePerTick(VANILLA_SPEED);
            changed = true;
        }
        if (level.getDayTime() != EditorQuietRules.MIDDAY_TICKS) {
            level.setDayTime(EditorQuietRules.MIDDAY_TICKS);
            changed = true;
        }
        if (changed) {
            level.getServer().forceTimeSynchronization();
        }
    }
}
