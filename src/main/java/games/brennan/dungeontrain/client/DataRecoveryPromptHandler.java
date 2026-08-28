package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.data.PlayerDataRecovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Surfaces {@link DataRecoveryScreen} at the title screen when this install looks like it lost its
 * Dungeon Train data and something can be put back.
 *
 * <p>Structure is the one {@link ConfigDeviationPromptHandler} uses: arm on
 * {@code ScreenEvent.Init.Post} for the title screen, open after a short tick delay, bail if the
 * player navigated away meanwhile so it never steals a click from someone already heading into a
 * world. That navigate-away guard is also what keeps this out of the way of the other title-screen
 * prompts and of the cinematic preload gate.</p>
 *
 * <p>Title screen rather than world join, for the same reason as the config prompt: the data has to
 * be back before a world loads, or the run has already started without it. The scan is a bounded
 * directory walk and runs <b>once per session</b>, cached.</p>
 *
 * <p>"Don't ask again" is recorded as a marker file in the data root rather than in the client
 * config — the answer is about this install's data, so it belongs next to the data, and it survives
 * a config folder being replaced by the very kind of update that caused the loss.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DataRecoveryPromptHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ticks to wait after the title screen initialises before opening.
     *
     * <p>Deliberately <b>shorter</b> than the other title-screen prompts (the developer welcome and
     * the config-deviation notice both use 24). They all disarm themselves the moment another
     * screen takes over, so whichever opens first wins the visit and the rest wait for the player
     * to close it — and "your builds and progress are missing" outranks anything else we might say
     * here. Found the hard way: at 30 ticks the developer popup opened first and this one silently
     * queued behind it.</p>
     */
    private static final int OPEN_DELAY_TICKS = 18;

    static final String DISMISSED_MARKER = "recovery-dismissed.marker";

    private static int openDelayRemaining = -1;
    private static TitleScreen pendingParent = null;
    private static boolean openedThisSession = false;

    /** Session cache of the scan: {@code null} until the first title screen. */
    private static List<PlayerDataRecovery.Candidate> candidates = null;

    private DataRecoveryPromptHandler() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (openDelayRemaining <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen)) {
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }
        openDelayRemaining--;
        if (openDelayRemaining != 0) return;

        TitleScreen parent = pendingParent;
        openDelayRemaining = -1;
        pendingParent = null;
        if (parent == null || mc.screen != parent || !shouldPrompt()) return;
        openedThisSession = true;
        LOGGER.info("[DungeonTrain] Data recovery: offering {} candidate(s) at the title screen",
            candidates.size());
        mc.setScreen(new DataRecoveryScreen(parent, candidates));
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (openedThisSession || openDelayRemaining > 0) return;
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) return;
        if (!shouldPrompt()) return;
        openDelayRemaining = OPEN_DELAY_TICKS;
        pendingParent = titleScreen;
    }

    /** Is this install missing its data, with somewhere to get it back from? */
    private static boolean shouldPrompt() {
        if (candidates == null) candidates = scan();
        return !candidates.isEmpty();
    }

    private static List<PlayerDataRecovery.Candidate> scan() {
        try {
            if (Files.exists(dismissedMarker())) return List.of();
            Path dataRoot = PlayerDataPaths.root();
            if (!PlayerDataRecovery.looksEmptied(dataRoot, PlayerDataPaths.configRoot(),
                    PlayerDataPaths.dtpacksRoot())) {
                return List.of();
            }
            List<PlayerDataRecovery.Candidate> found =
                PlayerDataRecovery.findCandidates(dataRoot, FMLPaths.GAMEDIR.get());
            if (!found.isEmpty()) {
                LOGGER.info("[DungeonTrain] Data recovery: this install has no Dungeon Train data "
                    + "and {} candidate(s) were found to restore from.", found.size());
            }
            return found;
        } catch (Exception e) {
            // A recovery offer must never be the thing that stops the game reaching the menu.
            LOGGER.warn("[DungeonTrain] Data recovery scan failed: {}", e.toString());
            return List.of();
        }
    }

    /**
     * The player answered. {@code permanent} writes the marker so the offer never returns; a
     * restore passes {@code false}, because once the data is back the loss signature no longer
     * matches and the scan won't fire anyway.
     */
    static void onAnswered(boolean permanent) {
        candidates = List.of();
        if (!permanent) return;
        try {
            Path marker = dismissedMarker();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Dungeon Train: recovery offer dismissed by the player.\n");
            LOGGER.info("[DungeonTrain] Data recovery: dismissed permanently.");
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Data recovery: couldn't record the dismissal: {}", e.toString());
        }
    }

    private static Path dismissedMarker() {
        return PlayerDataPaths.root().resolve(DISMISSED_MARKER);
    }
}
