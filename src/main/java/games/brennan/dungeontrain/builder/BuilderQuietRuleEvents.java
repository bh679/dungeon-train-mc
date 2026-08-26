package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Re-applies {@link BuilderQuietRules} on every start of a Train Builder world.
 *
 * <p>The rules are already baked in at creation, which is enough for a world made today and left
 * alone. This hook is what covers the two cases where that is not the whole story: a builder world
 * saved before those defaults existed, and one where the rules were changed after the fact — by
 * {@code /gamerule}, or by editing {@code level.dat}. Without it, "the clock is stopped in the
 * builder" is true of new worlds only, which is the kind of rule that looks broken rather than
 * configurable.</p>
 *
 * <p>Gated on the <b>overworld's</b> dimension type: a builder world's overworld <em>is</em> the
 * builder dimension (it is the only dimension the preset defines), so that one check is what makes
 * this inert in every ordinary world — the rules here are server-wide, and switching off mob
 * spawning in somebody's survival save would be a serious bug.</p>
 *
 * <p>Hooked to {@code ServerStartedEvent} to match {@code PerfTestModeEvents}, and because the
 * rules are server-scoped rather than per-level — there is nothing gained by waiting for a level
 * load, and the hook fires once per session rather than once per join.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BuilderQuietRuleEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuilderQuietRuleEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (server.overworld() == null
                || !server.overworld().dimensionTypeRegistration()
                        .is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return; // not a builder world — leave the rules alone
        }
        BuilderQuietRules.apply(server.getGameRules(), server);
        LOGGER.info("[DungeonTrain] Builder world — {} quiet game rules applied "
                + "(daylight, weather, mob spawning off).", BuilderQuietRules.RULE_COUNT);
    }
}
