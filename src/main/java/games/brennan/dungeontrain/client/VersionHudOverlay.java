package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
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

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        LayeredDraw.Layer overlay = (graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) {
                return;
            }
            if (mc.player != null && mc.player.isSpectator()) {
                return;
            }
            // Step aside when the editor status HUD is active — keeps the
            // top-of-screen area uncluttered while the player is editing.
            if (EditorStatusHudOverlay.isActive()) {
                return;
            }
            // Release builds run on `main`; the version/branch suffix is dev-only noise there.
            if ("main".equals(VersionInfo.BRANCH)) {
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
