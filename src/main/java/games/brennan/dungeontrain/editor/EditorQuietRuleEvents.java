package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Re-applies {@link EditorQuietRules} on every start of a Train Editor world, and rests the clock
 * at midday.
 *
 * <p>The rules are baked in at creation, which is enough for a world made today and left alone.
 * This hook covers the two cases where that is not the whole story: an editor world saved before
 * the defaults existed, and one where a rule was changed after the fact by {@code /gamerule}.
 * Without it, "nothing wanders into your plots" and "it is always noon" would be true of new editor
 * worlds only — and a mob that wanders in now ends up saved into somebody's template.</p>
 *
 * <h2>Two markers</h2>
 * <p>Editor worlds made since {@link EditorWorldLayout} exist are their own dimension type, and
 * that is the strong marker this checks first — the same way {@code BuilderQuietRuleEvents} gates
 * on the builder's type. Editor worlds made before that are ordinary worlds on the default DT
 * preset, and the only thing distinguishing them is the {@link #EDITOR_WORLD_PREFIX} name they were
 * given; that weaker match stays so those saves keep their quiet rule too. A player who hand-names
 * a save {@code train editor 3} gets no natural mob spawning in it, which is a quiet world rather
 * than a broken one.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorQuietRuleEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The name {@code DevQuickWorldHandler.launchEditorWorld} gives every editor world it creates.
     *
     * <p>Lives here, on the common side, because the launcher is client-only and this hook is not.
     * The launcher reads it from here rather than keeping its own copy — two spellings of the same
     * prefix would mean editor worlds that quietly do not get the rule.</p>
     */
    public static final String EDITOR_WORLD_PREFIX = "train editor ";

    private EditorQuietRuleEvents() {}

    /** Whether {@code levelName} is one of the worlds the Train Editor button creates. */
    public static boolean isEditorWorldName(String levelName) {
        return levelName != null && levelName.startsWith(EDITOR_WORLD_PREFIX);
    }

    /**
     * Whether {@code level} belongs to a Train Editor world — either kind: one on the editor's own
     * dimension type, or a legacy one recognised by its name.
     *
     * <p>The one test the start-up hook and {@link EditorClock} share, so the world that gets the
     * quiet rules is exactly the world whose clock the editor runs.</p>
     */
    public static boolean isEditorWorld(ServerLevel level) {
        if (level == null) return false;
        if (EditorWorldLayout.isEditorWorld(level)) return true;
        MinecraftServer server = level.getServer();
        return server.getWorldData() != null
            && isEditorWorldName(server.getWorldData().getLevelName());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!isEditorWorld(server.overworld())) {
            return; // not an editor world — leave the rules alone
        }
        EditorQuietRules.apply(server.getGameRules(), server);
        // Start at a stopped noon whatever the world was left at — including the 10x clock a
        // Day/Night plot runs, which is saved in level.dat when the author quits standing in one.
        EditorClock.restMidday(server.overworld());
        LOGGER.info("[DungeonTrain] Train Editor world — {} quiet game rules applied "
                + "(natural mob spawning off, clock stopped at midday).",
            EditorQuietRules.RULE_COUNT);
    }
}
