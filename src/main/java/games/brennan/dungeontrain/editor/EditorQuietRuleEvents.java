package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Re-applies {@link EditorQuietRules} on every start of a Train Editor world.
 *
 * <p>The rule is baked in at creation, which is enough for a world made today and left alone. This
 * hook covers the two cases where that is not the whole story: an editor world saved before the
 * default existed, and one where the rule was changed after the fact by {@code /gamerule}. Without
 * it, "nothing wanders into your plots" would be true of new editor worlds only — and a mob that
 * wanders in now ends up saved into somebody's template.</p>
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

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        boolean editorWorld = EditorWorldLayout.isEditorWorld(server.overworld())
                || (server.getWorldData() != null
                    && isEditorWorldName(server.getWorldData().getLevelName()));
        if (!editorWorld) {
            return; // not an editor world — leave the rules alone
        }
        EditorQuietRules.apply(server.getGameRules(), server);
        LOGGER.info("[DungeonTrain] Train Editor world — {} quiet game rule applied "
                + "(natural mob spawning off, so nothing wanders into a plot and gets saved).",
            EditorQuietRules.RULE_COUNT);
    }
}
