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
 * <h2>Why the name, and not the dimension</h2>
 * <p>{@code BuilderQuietRuleEvents} gates on the overworld's dimension type, because a builder world
 * <em>is</em> its own dimension. An editor world has no such marker: {@code launchEditorWorld} builds
 * an ordinary world on the default DT preset (created with {@code startsWithTrain = false}, but so
 * is any survival world whose author turned the train off), and the only thing distinguishing it is
 * the {@link #EDITOR_WORLD_PREFIX} name it is given. So that is what this matches.</p>
 *
 * <p><b>It is a weaker marker and worth saying so.</b> A player who hand-names a save
 * {@code train editor 3} gets no natural mob spawning in it. The stronger alternative — a flag saved
 * into {@code DungeonTrainWorldData}, armed through {@code PendingWorldChoices} the way
 * {@code startsWithTrain} is — is a good deal more plumbing than the difference earns, and the
 * failure mode here is a quiet world rather than a broken one. Revisit if editor worlds ever grow a
 * preset of their own, which would give this a real marker to use.</p>
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
        if (server.getWorldData() == null
                || !isEditorWorldName(server.getWorldData().getLevelName())) {
            return; // not an editor world — leave the rules alone
        }
        EditorQuietRules.apply(server.getGameRules(), server);
        LOGGER.info("[DungeonTrain] Train Editor world — {} quiet game rule applied "
                + "(natural mob spawning off, so nothing wanders into a plot and gets saved).",
            EditorQuietRules.RULE_COUNT);
    }
}
