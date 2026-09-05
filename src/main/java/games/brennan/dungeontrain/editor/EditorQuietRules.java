package games.brennan.dungeontrain.editor;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;

/**
 * The game rules a Train Editor world is meant to sit under: nothing wanders into a plot.
 *
 * <p>An editor plot is authored content, and since a template now carries the mobs standing in it
 * ({@link games.brennan.dungeontrain.template.TemplateDecor}), anything that walks in gets saved as
 * part of somebody's build. Switching natural spawning off is what makes "a mob in a plot is a mob
 * the author placed" true, which is the assumption the capture rests on.</p>
 *
 * <p>The sibling of {@code BuilderQuietRules}, and deliberately <b>narrower</b>: that one also stops
 * the clock and the weather, because a builder world exists to show you one build under fixed light.
 * An editor world is a normal world with a plot grid above it — an author may well want the sun to
 * move — so this switches off the one rule that would otherwise corrupt what they save.</p>
 *
 * <p>One typed implementation shared by both entry points, so the two cannot drift apart:</p>
 * <ul>
 *   <li>{@code DevQuickWorldHandler#launchEditorWorld} — applied to the fresh {@link GameRules} at
 *       world <i>creation</i>, so the setting is baked into {@code level.dat}.</li>
 *   <li>{@link EditorQuietRuleEvents} — re-applied on server start, which is what covers an editor
 *       world created before this existed and one where somebody ran {@code /gamerule}.</li>
 * </ul>
 */
public final class EditorQuietRules {

    /** How many rules {@link #apply} switches off — for log lines. */
    public static final int RULE_COUNT = 1;

    private EditorQuietRules() {}

    /**
     * Switch off natural mob spawning.
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
    }
}
