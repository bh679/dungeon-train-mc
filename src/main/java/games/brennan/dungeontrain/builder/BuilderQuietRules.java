package games.brennan.dungeontrain.builder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;

/**
 * The game rules a Train Builder world is meant to sit under: no clock, no weather, no mobs.
 *
 * <p>These are properties of the <em>mode</em> rather than of one world. A builder world exists to
 * show you what you are building and nothing else — light that changes under a build, rain across
 * it, and mobs wandering into it are all noise against that one job. The dimension type already
 * pins the sun at noon ({@code fixed_time: 6000} in {@code builder.json}); these rules are what stop
 * the rest of the background from moving.</p>
 *
 * <p>One typed implementation shared by both entry points, so the two cannot drift apart:</p>
 * <ul>
 *   <li>{@code DevQuickWorldHandler#launchBuilderWorld} — applied to the fresh {@link GameRules} at
 *       world <i>creation</i>, so the settings are baked into {@code level.dat}.</li>
 *   <li>{@link BuilderQuietRuleEvents} — re-applied on server start, which is what covers a world
 *       created before these defaults existed and one where somebody ran {@code /gamerule}.</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> a full copy of {@link games.brennan.dungeontrain.perf.PerfTestMode}'s
 * quiet set: fire tick and random ticking stay on, because a builder is placing blocks whose
 * behaviour they want to see.</p>
 */
public final class BuilderQuietRules {

    /** How many rules {@link #apply} switches off — for log lines. */
    public static final int RULE_COUNT = 3;

    private BuilderQuietRules() {}

    /**
     * Switch off the background motion a build should not have to sit under.
     *
     * <p>Idempotent — setting a rule to the value it already holds is a no-op — so this is safe to
     * run on every server start rather than only on the first.</p>
     *
     * @param rules  the rule set to mutate
     * @param server passed through so live rule-change listeners fire; {@code null} at world
     *               creation, when no server exists yet
     */
    public static void apply(GameRules rules, @Nullable MinecraftServer server) {
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
    }
}
