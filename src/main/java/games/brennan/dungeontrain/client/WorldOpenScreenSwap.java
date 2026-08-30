package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderWorldCheck;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Substitutes {@link WorldOpenLoadingScreen} for the vanilla {@link GenericMessageScreen} that
 * opens a world load, so the join is themed from its very first frame.
 *
 * <p>{@code WorldOpenFlows} calls {@code Minecraft.forceSetScreen(new GenericMessageScreen(...))}
 * — which routes through {@code setScreen}, so {@link ScreenEvent.Opening} fires and the swap can
 * be made in the same synchronous call, the same trick {@link CinematicPreloadGate} uses at the
 * other end of the sequence. A screen swap rather than a mixin because
 * {@code GenericMessageScreen} does not declare {@code render} at all (it inherits
 * {@code Screen.render}), so there is nothing there to inject into.</p>
 *
 * <p><b>Only the two world-open phases are swapped.</b> The same vanilla class is what DT hands
 * {@code Minecraft.disconnect} for saving and for the leaving-the-world farewell (see
 * {@code DeathScreenLayoutHandler}); those carry their own titles and must keep the vanilla look,
 * so the match is on the title key, never on the class alone.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class WorldOpenScreenSwap {

    /** Vanilla's two world-open phases — the only {@link GenericMessageScreen}s DT replaces. */
    private static final String READ_KEY = "selectWorld.data_read";
    private static final String LOAD_KEY = "selectWorld.resource_load";

    private WorldOpenScreenSwap() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof GenericMessageScreen opening)) return;
        if (!isWorldOpenPhase(opening.getTitle())) return;
        if (BuilderWorldCheck.isBuilderWorld()) return; // sandbox keeps vanilla chrome
        // From here the join is under way — this is what tells the vanilla screens that follow
        // (ProgressScreen, ReceivingLevelScreen) they are part of it and should be themed. It also
        // starts JoinIntroFade's clock, so the hand-off is timed from this exact instant.
        boolean firstPhase = !(event.getCurrentScreen() instanceof WorldOpenLoadingScreen);
        if (firstPhase) {
            LoadingSequenceProgress.beginJoin();
        }
        // The screen the player pressed the button on, handed over so it can be faded out rather
        // than cut away. Only on the first phase — the second one is replacing our own screen, and
        // fading that into itself would restart the hand-off midway through.
        event.setNewScreen(new WorldOpenLoadingScreen(firstPhase ? event.getCurrentScreen() : null));
    }

    /**
     * True only for vanilla's world-open messages. DT's own save / farewell screens use the same
     * class with their own titles and are left alone.
     */
    private static boolean isWorldOpenPhase(Component title) {
        if (!(title.getContents() instanceof TranslatableContents translatable)) {
            return false;
        }
        String key = translatable.getKey();
        return READ_KEY.equals(key) || LOAD_KEY.equals(key);
    }
}
