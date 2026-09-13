package games.brennan.dungeontrain.editor;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;

/**
 * The game rules a Train Editor world is meant to sit under: nothing wanders into a plot, and the
 * clock does not move.
 *
 * <p>An editor plot is authored content, and since a template now carries the mobs standing in it
 * ({@link games.brennan.dungeontrain.template.TemplateDecor}), anything that walks in gets saved as
 * part of somebody's build. Switching natural spawning off is what makes "a mob in a plot is a mob
 * the author placed" true, which is the assumption the capture rests on.</p>
 *
 * <p>The clock is held at midday so every plot is authored under the same full light — the same
 * decision the Train Builder makes, though by a different mechanism: the builder pins the sun in
 * its dimension type, the editor stops the clock, because one Sky an author can give a dimensional
 * carriage is Day/Night and a pinned sun could never show it. {@link EditorClock} owns that
 * exception — it runs the clock at speed while somebody stands in such a plot and rests it at
 * {@link #MIDDAY_TICKS} the moment they step out — and {@link EditorQuietRuleEvents} rests it on
 * every start.</p>
 *
 * <p>The sibling of {@code BuilderQuietRules}, minus its weather rule — the editor world has no
 * terrain for rain to fall on.</p>
 *
 * <p>One typed implementation shared by both entry points, so the two cannot drift apart:</p>
 * <ul>
 *   <li>{@code DevQuickWorldHandler#launchEditorWorld} — applied to the fresh {@link GameRules} at
 *       world <i>creation</i>, so the settings are baked into {@code level.dat}.</li>
 *   <li>{@link EditorQuietRuleEvents} — re-applied on server start, which is what covers an editor
 *       world created before these existed and one where somebody ran {@code /gamerule}.</li>
 * </ul>
 */
public final class EditorQuietRules {

    /** How many rules {@link #apply} switches off — for log lines. */
    public static final int RULE_COUNT = 2;

    /** The day time the editor rests at: noon. */
    public static final long MIDDAY_TICKS = 6000L;

    private EditorQuietRules() {}

    /**
     * Switch off natural mob spawning and the daylight cycle.
     *
     * <p>Idempotent — setting a rule to the value it already holds is a no-op — so this is safe to
     * run on every server start rather than only on the first.</p>
     *
     * @param rules  the rule set to mutate
     * @param server passed through so live rule-change listeners fire; {@code null} at world
     *               creation, when no server exists yet
     */
    public static void apply(GameRules rules, @Nullable MinecraftServer server) {
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
    }
}
