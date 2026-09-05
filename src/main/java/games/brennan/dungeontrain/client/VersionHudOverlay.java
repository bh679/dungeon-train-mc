package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.ActivityStatePacket;
import games.brennan.dungeontrain.net.CarriageGroupGapPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Client-only HUD overlay: draws "Dungeon Train v&lt;version&gt; (&lt;branch&gt;)"
 * in the top-left corner in-game, optionally suffixed with the player's
 * closest carriage index (pushed by the server via
 * {@link games.brennan.dungeontrain.net.CarriageIndexPacket}). Respects F1
 * (hideGui); F3 debug overlay draws over this, which is intentional.
 *
 * <p>The companion {@link games.brennan.dungeontrain.client.version.VersionStatusButton}
 * handles the main menu case (and also folds in the GitHub release-check status).</p>
 */
@EventBusSubscriber(
        modid = DungeonTrain.MOD_ID,
        value = Dist.CLIENT
)
public final class VersionHudOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Mutated on the client main thread from the packet handler; read on the
    // same thread during HUD rendering. Volatile for safe publication in case
    // Forge routes the render call through a different thread in the future.
    private static volatile boolean carriagePresent = false;
    private static volatile int carriageIndex = 0;
    private static volatile boolean boardingProgressPresent = false;
    private static volatile int travelledCarriageIndex = 0;
    private static volatile int difficultyTier = 0;
    private static volatile ActivityStatePacket activityState = null;

    private VersionHudOverlay() {}

    /**
     * Called from {@code CarriageIndexPacket.handle} on the client main
     * thread. {@code present=false} hides the suffix; otherwise the signed
     * index is shown.
     */
    public static void setCarriageIndex(boolean present, int pIdx) {
        carriagePresent = present;
        carriageIndex = pIdx;
    }

    /**
     * Called from {@code BoardingProgressPacket.handle} on the client main
     * thread. Drives the dev-HUD "Diff:" read-out for the boarding-gated
     * difficulty system.
     */
    public static void setBoardingProgress(int travelled, int tier) {
        travelledCarriageIndex = travelled;
        difficultyTier = tier;
        boardingProgressPresent = true;
    }

    /**
     * The live carriages-travelled run counter pushed by
     * {@code BoardingProgressPacket} (the same figure the FALL death page shows
     * as {@code cartsTravelled}); {@code 0} before the first update. Read by
     * {@link games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector}
     * to pace ride snapshots by journey progress.
     */
    public static int travelledCarriageIndex() {
        return travelledCarriageIndex;
    }

    /**
     * The live difficulty level (tier) pushed by {@code BoardingProgressPacket} — the server-computed
     * {@code tierForTravelled(...)} result; {@code 0} before the first update. Read by
     * {@link games.brennan.dungeontrain.client.snapshot.SnapshotMeta} to stamp each ride photo, since
     * the tier formula's config is server-only and can't be recomputed client-side.
     */
    public static int difficultyLevel() {
        return difficultyTier;
    }

    /**
     * Called from {@code ActivityStatePacket.handle} on the client main thread. Drives the dev-HUD
     * "Time:" read-out — whether time on the train is banking, and what stopped it.
     */
    public static void setActivityState(ActivityStatePacket state) {
        activityState = state;
    }

    /** {@code M:SS}, or {@code H:MM:SS} once it runs past an hour. */
    private static String formatClock(long ticks) {
        long totalSeconds = Math.max(0L, ticks) / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
            ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
            : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    /**
     * The state half of the "Time:" line. Mirrors
     * {@code PlayerActivityTracker.Reason} ordinals — the server owns the rules, this only names
     * them.
     */
    private static String activityLabel(ActivityStatePacket state) {
        return switch (state.reason()) {
            case 1 -> "⏸ paused";
            case 2 -> "⏸ mouse idle " + formatClock(state.stoppedSeconds() * 20L);
            case 3 -> "⏸ no input " + formatClock(state.stoppedSeconds() * 20L);
            case 4 -> "⏸ no progress " + state.carriagesInWindow() + "/3";
            default -> "▶ tracking";
        };
    }

    /**
     * Whether this HUD is putting anything in the top-left corner right now.
     *
     * <p>Shared with the render lambda rather than duplicated, so
     * {@link TrainDebugHudOverlay} — which stacks itself underneath — can never disagree with what
     * was actually drawn.</p>
     */
    static boolean isDrawing(Minecraft mc) {
        if (mc.options.hideGui) {
            return false;
        }
        if (mc.player != null && mc.player.isSpectator()) {
            return false;
        }
        // Step aside when the editor status HUD is active — keeps the
        // top-of-screen area uncluttered while the player is editing.
        if (EditorStatusHudOverlay.isActive()) {
            return false;
        }
        // Release builds run on `main`; the version/branch suffix is dev-only noise there.
        return !"main".equals(VersionInfo.BRANCH);
    }

    /**
     * How many lines {@link #isDrawing} would draw. Mirrors the render lambda's own branching; both
     * read the same statics in the same frame, so the count matches what lands on screen.
     */
    static int lineCount() {
        int lines = 1; // the version/carriage title line, always present when drawing
        if (boardingProgressPresent) {
            lines += 2; // Diff-Car + Diff-Level
        }
        if (activityState != null) {
            lines += 1; // Time: banking state + the train clock
        }
        if (carriagePresent && DebugFlagsState.hudDistance()
                && CarriageGroupGapState.findByCarriage(carriageIndex) != null) {
            lines += 1; // Δx to next group
        }
        return lines;
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        LayeredDraw.Layer overlay = (graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!isDrawing(mc)) {
                return;
            }
            String text = carriagePresent
                ? VersionInfo.DISPLAY + " — Carriage: " + formatSigned(carriageIndex)
                : VersionInfo.DISPLAY;
            HudText.drawScaled(graphics, mc.font, text, 4, 4, 0xFFFFFFFF, true);

            int line = 1;

            // Diff lines: shows the boarding-gated difficulty progression
            // counter ("diff-car") and the tier it resolves to ("diff-level").
            if (boardingProgressPresent) {
                String carText = "  Diff-Car: " + formatSigned(travelledCarriageIndex);
                HudText.drawScaled(graphics, mc.font, carText,
                    4, 4 + (HudText.scaledLineHeight(mc.font) + 1) * line,
                    0xFFFFD080, true);
                line++;
                String levelText = "  Diff-Level: " + difficultyTier;
                HudText.drawScaled(graphics, mc.font, levelText,
                    4, 4 + (HudText.scaledLineHeight(mc.font) + 1) * line,
                    0xFFFFD080, true);
                line++;
            }

            // Is time banking, and if not, which rule stopped it? Server-pushed, because the
            // idle rules and the counters both live there.
            ActivityStatePacket activity = activityState;
            if (activity != null) {
                String timeText = String.format(Locale.ROOT, "  Time: %s   train %s",
                    activityLabel(activity),
                    formatClock(activity.trainTimeTicks()));
                HudText.drawScaled(graphics, mc.font, timeText,
                    4, 4 + (HudText.scaledLineHeight(mc.font) + 1) * line,
                    activity.countingTrain() ? 0xFF80FF80 : 0xFFFFC060, true);
                line++;
            }

            // Distance from THIS group (the one the player is standing in)
            // to the next-higher-pIdx group in the same train. Only shown
            // when the player is in a tracked carriage AND that carriage's
            // group is not the leading group of its train.
            if (carriagePresent && DebugFlagsState.hudDistance()) {
                CarriageGroupGapPacket.Entry gap = CarriageGroupGapState.findByCarriage(carriageIndex);
                if (gap != null) {
                    String gapText = String.format(Locale.ROOT,
                        "  Δx to next group: %.2f blocks", gap.distance());
                    HudText.drawScaled(graphics, mc.font, gapText,
                        4, 4 + (HudText.scaledLineHeight(mc.font) + 1) * line,
                        0xFFCCFFCC, true);
                    line++;
                }
            }
        };

        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "version_hud"), overlay);
        LOGGER.info("Version HUD registered: {}", VersionInfo.DISPLAY);
    }

    private static String formatSigned(int n) {
        if (n > 0) return "+" + n;
        return Integer.toString(n);
    }
}
