package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.skybox.SkyboxStencil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The shader-compatibility read-out — <b>F3 + 5</b>, the sibling of the F3+4 train panel
 * ({@link TrainDebugHudOverlay}) and gated by the same grant.
 *
 * <p>It exists to make a screenshot self-explanatory. Each line states what Dungeon Train asked the
 * frame for; the rest of the screenshot is what the shader pack did with that request. Together
 * they say which of the two failure modes is in play — the mod never asked, or the pack discarded
 * the ask — which is the one distinction a compatibility matrix has to get right and the one a bare
 * screenshot cannot make.</p>
 *
 * <p>Drawn <b>top-right</b>, deliberately: the train panel, the version HUD and vanilla's own F3
 * screen all compete for the top-left, and both panels need to be legible in the same shot.</p>
 *
 * <p>Text is untranslated English, matching {@link TrainDebugHudOverlay} and vanilla's F3 screen —
 * a diagnostic surface read by the dev, not player-facing copy.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ShaderDiagnosticsHud {

    private static final int PAD = 3;
    private static final int MARGIN = 4;
    private static final int LINE_GAP = 1;
    private static final int BACKDROP = 0xA0000000;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    /** A line whose feature is idle this frame — nothing was asked for. */
    private static final int COLOR_IDLE = 0xFF999999;
    /** A line whose feature actively asked the frame for something. */
    private static final int COLOR_ACTIVE = 0xFF90FF90;
    /** A line reporting a feature this pack has switched off. */
    private static final int COLOR_OFF = 0xFFFF9090;

    private static final String NONE = "—";

    private ShaderDiagnosticsHud() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "shader_diagnostics_hud"),
            (LayeredDraw.Layer) (graphics, deltaTracker) -> render(graphics));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ShaderDiagnostics.reset();
    }

    private static void render(GuiGraphics graphics) {
        if (!TrainDebugState.permitted() || !ShaderDiagnostics.visible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.font == null) {
            return;
        }

        List<Line> lines = buildLines(mc);
        int lineHeight = HudText.scaledLineHeight(mc.font) + LINE_GAP;
        int widest = 0;
        for (Line line : lines) {
            widest = Math.max(widest, HudText.scaledWidth(mc.font, line.text()));
        }

        int panelHeight = lines.size() * lineHeight + PAD * 2 - LINE_GAP;
        int panelWidth = widest + PAD * 2;
        int left = Math.max(MARGIN, graphics.guiWidth() - MARGIN - panelWidth);
        int top = MARGIN;

        graphics.fill(left, top, left + panelWidth, top + panelHeight, BACKDROP);

        int y = top + PAD;
        for (Line line : lines) {
            HudText.drawScaled(graphics, mc.font, line.text(), left + PAD, y, line.color(), true);
            y += lineHeight;
        }

        // Clear the per-frame record now that it has been drawn, so a hook that does not run next
        // frame reads as "asked for nothing" rather than repeating this frame's.
        ShaderDiagnostics.consumeFrame();
    }

    private static List<Line> buildLines(Minecraft mc) {
        List<Line> lines = new ArrayList<>(10);

        lines.add(new Line("DT shader compat", COLOR_TITLE));
        lines.add(new Line("Pack: " + ShaderCompat.describe(),
            ShaderCompat.active() ? COLOR_ACTIVE : COLOR_IDLE));

        GraphicsCapabilities.GraphicsMode mode = GraphicsCapabilities.graphicsMode();
        String fboStencil = ShaderDiagnostics.levelFboStencil();
        lines.add(new Line("Stencil: main " + yesNo(SkyboxStencil.isAvailable())
            + "  level fbo " + (fboStencil.isEmpty() ? NONE : fboStencil)
            + "   Gfx: " + (mode == null ? NONE : mode.name().toLowerCase(Locale.ROOT))
            + "   DH: " + yesNo(GraphicsCapabilities.distantHorizonsActive()), COLOR_IDLE));

        // Band intensity at the camera. Pure reads of world-X — safe to call from the HUD, unlike
        // the room and corridor eases, which advance when read and so are recorded by their hooks.
        double camX = cameraX(mc);
        double tVoid = ClientVoidBand.endSkyIntensityAt(camX);
        double tNether = ClientNetherBand.netherIntensityAt(camX);
        double tFlip = ClientUpsideDownBand.upsideDownIntensityAt(camX);
        boolean inBand = tVoid > 0.0 || tNether > 0.0 || tFlip > 0.0;
        lines.add(new Line(String.format(Locale.ROOT,
            "Band t: void %.3f  nether %.3f  flip %.3f", tVoid, tNether, tFlip),
            inBand ? COLOR_ACTIVE : COLOR_IDLE));

        float aVoid = ShaderDiagnostics.skyVoid();
        float aNether = ShaderDiagnostics.skyNether();
        float aFlip = ShaderDiagnostics.skyUpsideDown();
        boolean drewSky = aVoid > 0.0f || aNether > 0.0f || aFlip > 0.0f;
        lines.add(new Line("Band sky drawn: " + (drewSky
            ? String.format(Locale.ROOT, "void %.3f  nether %.3f  flip %.3f", aVoid, aNether, aFlip)
            : NONE), drewSky ? COLOR_ACTIVE : COLOR_IDLE));

        String fogColorSource = ShaderDiagnostics.fogColorSource();
        boolean tintedFog = !fogColorSource.isEmpty();
        lines.add(new Line("Fog colour: " + (tintedFog
            ? fogColorSource + " " + ShaderDiagnostics.hex(ShaderDiagnostics.fogColorIn())
                + " -> " + ShaderDiagnostics.hex(ShaderDiagnostics.fogColorOut())
            : NONE), tintedFog ? COLOR_ACTIVE : COLOR_IDLE));

        boolean askedFog = ShaderDiagnostics.fogDistanceAsked();
        lines.add(new Line("Fog dist: " + (askedFog
            ? String.format(Locale.ROOT, "far %.1f -> %.1f  near %.1f  cancelled %s",
                ShaderDiagnostics.fogVanillaFar(), ShaderDiagnostics.fogFar(),
                ShaderDiagnostics.fogNear(), yesNo(ShaderDiagnostics.fogCancelled()))
            : NONE), askedFog && ShaderDiagnostics.fogCancelled() ? COLOR_ACTIVE : COLOR_IDLE));

        // Clouds: DT hides them over the End/Nether bands and sinks them in the upside-down band,
        // both through vanilla's cloud pass. "hook no" means that pass never ran — the pack draws
        // its own clouds and neither behaviour is in effect.
        boolean cloudHook = ShaderDiagnostics.cloudsHookRan();
        String clouds;
        if (!cloudHook) {
            clouds = "vanilla pass never ran (pack draws its own)";
        } else if (ShaderDiagnostics.cloudsCancelled()) {
            clouds = "hidden by DT";
        } else {
            float vanillaY = ShaderDiagnostics.cloudHeightVanilla();
            float appliedY = ShaderDiagnostics.cloudHeightApplied();
            clouds = vanillaY > 0.0f
                ? String.format(Locale.ROOT, "shown, plane %.0f -> %.0f", vanillaY, appliedY)
                : "shown, plane unchanged";
        }
        lines.add(new Line("Clouds: " + clouds, cloudHook ? COLOR_ACTIVE : COLOR_OFF));

        boolean skyboxAllowed = ShaderCompat.allows(ShaderCompat.Feature.SKYBOX_BLOCKS);
        if (!skyboxAllowed) {
            lines.add(new Line("Skybox blocks: " + ShaderCompat.reason(ShaderCompat.Feature.SKYBOX_BLOCKS),
                COLOR_OFF));
        } else {
            int cubes = ShaderDiagnostics.skyboxCubes();
            lines.add(new Line("Skybox blocks: " + (cubes > 0
                ? cubes + " cubes [" + ShaderDiagnostics.skyboxVariants() + "] stencil "
                    + onOff(ShaderDiagnostics.skyboxStencil()) + " drew " + yesNo(ShaderDiagnostics.skyboxDrew())
                : "none on screen"), cubes > 0 ? COLOR_ACTIVE : COLOR_IDLE));
        }

        float reopenBefore = ShaderDiagnostics.reopenBefore();
        if (reopenBefore >= 0.0f) {
            lines.add(new Line(String.format(Locale.ROOT, "Reopen: centre depth %.5f -> %.5f  stencil after mark 0x%02X",
                reopenBefore, ShaderDiagnostics.reopenAfter(), ShaderDiagnostics.reopenStencil()), COLOR_ACTIVE));
        }

        String world = games.brennan.dungeontrain.client.shader.ShaderWorld.describe();
        lines.add(new Line("Shader world: " + (world.isEmpty() ? "overworld (pack default)" : world),
            world.isEmpty() ? COLOR_IDLE : COLOR_ACTIVE));

        String postPass = games.brennan.dungeontrain.client.shader.PostFogPass.lastDrawn();
        lines.add(new Line("Post pass: " + (postPass.isEmpty() ? NONE : postPass),
            postPass.isEmpty() ? COLOR_IDLE : COLOR_ACTIVE));

        float roomT = ShaderDiagnostics.roomSkyT();
        lines.add(new Line("Room sky: " + (roomT > 0.0f
            ? ShaderDiagnostics.roomSkyKind()
                + " t=" + ShaderDiagnostics.fmt(roomT)
                + " lift=" + ShaderDiagnostics.fmt(ShaderDiagnostics.roomSkyLift())
            : NONE), roomT > 0.0f ? COLOR_ACTIVE : COLOR_IDLE));

        float crossT = ClientPortalCrossing.current();
        lines.add(new Line("Transition: " + (crossT > 0.0f
            ? "t=" + ShaderDiagnostics.fmt(crossT)
                + " applied=" + ShaderDiagnostics.fmt(ShaderDiagnostics.crossingT())
            : NONE), crossT > 0.0f ? COLOR_ACTIVE : COLOR_IDLE));

        return lines;
    }

    /** The render camera's world-X, or {@code 0} before one exists. */
    private static double cameraX(Minecraft mc) {
        if (mc.gameRenderer == null) return 0.0;
        Vec3 pos = mc.gameRenderer.getMainCamera().getPosition();
        return pos == null ? 0.0 : pos.x;
    }

    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    private record Line(String text, int color) {}
}
