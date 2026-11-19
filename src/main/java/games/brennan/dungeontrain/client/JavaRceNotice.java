package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.version.LauncherDetector;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.util.JavaRceCheck;
import games.brennan.dungeontrain.util.JavaSetup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Warns a player whose Java is exposed to CVE-2026-114938 ("Java4Shell"), with a card over the
 * title screen ({@link JavaRcePromptScreen}) once per game session.
 *
 * <p>Who is warned is {@link JavaRceCheck}'s call: an x86-64 AVX-512 PC on an affected Java with
 * the Base64 intrinsic on. Apple silicon and older CPUs never see it. Adding the JVM argument
 * {@link JavaRceCheck#JVM_ARGUMENT} turns the intrinsic off, so the card stops by itself once the
 * fix is in, which is how the player knows it worked.</p>
 *
 * <p>"Don't show again" is remembered per Java runtime version, so a player who moves to another
 * (still unfixed) Java is told again. Opens the same tick-driven way as {@link LowMemoryNotice}:
 * after the loading overlay has gone, behind any other card that is already up.</p>
 *
 * <p>Dev: {@code -Ddungeontrain.javaRceWarning=force} shows the card on any machine and ignores the
 * dismissal, for screenshots and testing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class JavaRceNotice {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String FORCE_PROPERTY = "dungeontrain.javaRceWarning";

    private static final int OPEN_DELAY_TICKS = 24;

    private static int openDelayRemaining = -1;
    private static TitleScreen pendingParent;
    private static boolean decidedThisSession;

    private JavaRceNotice() {}

    /**
     * Pure: should the card open for this verdict, given the runtime the player last dismissed it
     * for. {@code forced} is the dev override.
     */
    static boolean shouldShow(JavaRceCheck.Verdict verdict, String runtimeVersion, String dismissedFor, boolean forced) {
        if (forced) return true;
        if (verdict != JavaRceCheck.Verdict.AT_RISK) return false;
        return dismissedFor == null || dismissedFor.isEmpty() || !dismissedFor.equals(runtimeVersion);
    }

    static String runtimeVersion() {
        return System.getProperty("java.runtime.version", "");
    }

    /** The translation key for where this player's launcher keeps its JVM arguments. */
    static String whereKey(LauncherDetector.Launcher launcher) {
        String suffix = switch (launcher) {
            case MINECRAFT_LAUNCHER -> "minecraft_launcher";
            case CURSEFORGE -> "curseforge";
            case MODRINTH -> "modrinth";
            case PRISM -> "prism";
            case MULTIMC -> "multimc";
            case ATLAUNCHER -> "atlauncher";
            case UNKNOWN -> "unknown";
        };
        return "gui.dungeontrain.java_rce.where." + suffix;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (decidedThisSession) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen titleScreen) || mc.getOverlay() != null) {
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }
        if (openDelayRemaining < 0) {
            openDelayRemaining = OPEN_DELAY_TICKS;
            pendingParent = titleScreen;
            return;
        }
        if (--openDelayRemaining > 0) return;

        TitleScreen parent = pendingParent;
        openDelayRemaining = -1;
        pendingParent = null;
        if (parent == null || mc.screen != parent) return;
        decidedThisSession = true;

        JavaRceCheck.Verdict verdict = JavaRceCheck.current();
        if (verdict == JavaRceCheck.Verdict.AT_RISK) {
            LOGGER.warn(JavaRceCheck.describe(verdict));
        } else {
            LOGGER.info(JavaRceCheck.describe(verdict));
        }
        boolean forced = "force".equals(System.getProperty(FORCE_PROPERTY));
        if (!shouldShow(verdict, runtimeVersion(), ClientDisplayConfig.getJavaRceWarningDismissedFor(), forced)) return;
        mc.setScreen(new JavaRcePromptScreen(parent, whereKey(LauncherDetector.launcher()), JavaSetup.current()));
    }
}
