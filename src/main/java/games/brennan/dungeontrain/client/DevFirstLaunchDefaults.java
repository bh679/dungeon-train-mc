package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

/**
 * First-launch client defaults for dev builds: skip the vanilla accessibility
 * (narration) onboarding screen and start muted.
 *
 * <p>A fresh checkout or worktree has an empty {@code run/} directory, so there is no
 * {@code options.txt} and vanilla treats the launch as a brand-new player's:
 * {@link Options#onboardAccessibility} defaults to {@code true} and
 * {@code Minecraft.addInitialScreens} pushes {@code AccessibilityOnboardingScreen} in
 * front of the title screen, at 100% master volume. Neither is ever wanted on a dev
 * build — it is by definition not somebody's first launch — so clear both here.</p>
 *
 * <h3>Why {@code FMLClientSetupEvent}</h3>
 * <p>Mod loading completes inside the initial {@code LoadingOverlay} reload;
 * {@code onResourceLoadFinished} → {@code onGameLoadFinished} → {@code buildInitialScreens}
 * → {@code addInitialScreens} all run <i>after</i> it. Clearing the flag here means the
 * onboarding screen is never constructed at all, rather than being cancelled after the
 * fact — which would also mean interfering with the {@code Runnable} chain that hands off
 * to the title screen.</p>
 *
 * <h3>Once, not every launch</h3>
 * <p>Gated on {@code onboardAccessibility} still being set, which is exactly the condition
 * under which the onboarding screen would have appeared. So this fires on a fresh profile
 * and never again: a dev who turns the volume back up keeps it across relaunches.</p>
 *
 * <p>Dev-vs-release is {@link DungeonTrain#isDevBuild()} — the same branch-ref signal the
 * title-screen dev row ({@link DevQuickWorldHandler}) and the version HUD read. Release
 * builds never enter this path, so players keep the vanilla onboarding.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DevFirstLaunchDefaults {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DevFirstLaunchDefaults() {}

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        if (!DungeonTrain.isDevBuild()) {
            return;
        }
        // enqueueWork because this setup event runs on a parallel mod-loading thread while
        // the work below reads and rewrites the live Options (and saves options.txt).
        event.enqueueWork(DevFirstLaunchDefaults::applyDevFirstLaunchDefaults);
    }

    private static void applyDevFirstLaunchDefaults() {
        Options options = Minecraft.getInstance().options;
        if (!options.onboardAccessibility) {
            return;   // profile already established — leave the dev's own settings alone
        }
        options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        // Vanilla's own "onboarding is done" call: clears the flag AND saves options.txt.
        options.onboardingAccessibilityFinished();
        LOGGER.info("Dev build, fresh profile: skipped the accessibility onboarding screen "
                + "and set master volume to 0%. Both are one-time — change them in Options "
                + "and the change sticks.");
    }
}
