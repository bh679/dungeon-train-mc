package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * "Is the player in a Train Builder world?" — the one client-side question that decides whether
 * DT shows its own chrome (themed loading screen, narrative death screen, Abandon This Run) or
 * gets out of the way.
 *
 * <p>The check is on the <b>dimension type</b> ({@code dungeontrain:builder}), not the dimension
 * key: a builder world's only dimension is {@code minecraft:overworld}, so the key that the rest
 * of the client code compares against ({@code VoidSkyEvents} and friends) can't tell it apart
 * from any other world.</p>
 *
 * <p>Two sources, in order, because the interesting moments happen at different points in the
 * join:</p>
 * <ol>
 *   <li><b>The client level</b> — available for the death screen and the pause menu.</li>
 *   <li><b>The integrated server's overworld</b> — available during {@code LevelLoadingScreen},
 *       which runs <em>before</em> the client level exists. This is also what makes the loading
 *       screen behave on a <em>reopened</em> builder world, not just a freshly created one.</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderWorldCheck {

    private BuilderWorldCheck() {}

    public static boolean isBuilderWorld() {
        Minecraft mc = Minecraft.getInstance();
        return shouldUseBuilderChrome(clientLevelIsBuilder(mc), integratedServerIsBuilder(mc));
    }

    /**
     * The decision, separated from the lookups so it can be tested without a client.
     *
     * <p>Both sources describe the same world, so they agree in practice; the level wins when it
     * is available because it is the more direct answer.</p>
     */
    public static boolean shouldUseBuilderChrome(Boolean levelIsBuilder, Boolean serverIsBuilder) {
        if (levelIsBuilder != null) {
            return levelIsBuilder;
        }
        return serverIsBuilder != null && serverIsBuilder;
    }

    /** @return null when there is no client level yet (so the caller falls through to the server) */
    private static Boolean clientLevelIsBuilder(Minecraft mc) {
        try {
            Level level = mc.level;
            return level == null ? null : level.dimensionTypeRegistration()
                    .is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE);
        } catch (Throwable t) {
            // A modded level that can't resolve its dimension type must not decide this either
            // way — fall through and let the server source answer.
            return null;
        }
    }

    /** @return null on a multiplayer client, or before the integrated server's overworld exists */
    private static Boolean integratedServerIsBuilder(Minecraft mc) {
        try {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) {
                return null;
            }
            ServerLevel overworld = server.overworld();
            return overworld == null ? null : overworld.dimensionTypeRegistration()
                    .is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE);
        } catch (Throwable t) {
            return null;
        }
    }
}
