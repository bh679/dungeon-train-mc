package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Dungeon Train debug panel — an F3-style read-out of the four facts a bug report needs:
 * mod build, the world's train generation seed, the player's carriage index, and that carriage's
 * cart type. Toggled with <b>F3 + 4</b>, captured by
 * {@link games.brennan.dungeontrain.mixin.client.KeyboardHandlerDebugChordMixin}.
 *
 * <p>Access is grant-gated: {@link TrainDebugState#permitted()} is false until the server says
 * otherwise, so for an ordinary player this layer never draws and the chord does nothing.</p>
 *
 * <p>Text is untranslated English, matching {@link VersionHudOverlay} and vanilla's own F3
 * screen — this is a diagnostic surface read by the dev, not player-facing copy.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TrainDebugHudOverlay {

    /** Gap between the panel edge and the text inside it. */
    private static final int PAD = 3;
    /** Distance from the screen edge to the panel. */
    private static final int MARGIN = 4;
    /** Space between lines, on top of the font's own line height. */
    private static final int LINE_GAP = 1;
    /** Breathing room between the dev version HUD's last line and the top of this panel. */
    private static final int STACK_GAP = 2;
    private static final int BACKDROP = 0xA0000000;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFFFD080;
    private static final int COLOR_EXPIRY = 0xFFCCFFCC;
    /** Placeholder for a field the client has no value for (off-train). */
    private static final String NONE = "—";

    private TrainDebugHudOverlay() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "train_debug_hud"),
            (LayeredDraw.Layer) (graphics, deltaTracker) -> render(graphics));
    }

    /**
     * The client statics outlive a disconnect, so a grant held on one server would otherwise
     * follow the player to the next one.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TrainDebugState.reset();
    }

    private static void render(GuiGraphics graphics) {
        if (!TrainDebugState.permitted() || !TrainDebugState.visible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.font == null) {
            return;
        }

        List<Line> lines = buildLines();
        int lineHeight = HudText.scaledLineHeight(mc.font) + LINE_GAP;
        int widest = 0;
        for (Line line : lines) {
            widest = Math.max(widest, HudText.scaledWidth(mc.font, line.text()));
        }

        int panelHeight = lines.size() * lineHeight + PAD * 2 - LINE_GAP;

        // Vanilla's F3 screen owns the top-left column, so step down to the bottom-left while it
        // is up rather than drawing two stacks of text on top of each other.
        int top;
        if (mc.getDebugOverlay().showDebugScreen()) {
            top = graphics.guiHeight() - MARGIN - panelHeight;
        } else if (VersionHudOverlay.isDrawing(mc)) {
            // Developer builds already have the version/Diff HUD in the top-left corner. Sit under
            // whatever it drew this frame rather than on top of it.
            top = MARGIN + VersionHudOverlay.lineCount() * lineHeight + STACK_GAP;
        } else {
            top = MARGIN;
        }

        graphics.fill(MARGIN, top,
            MARGIN + widest + PAD * 2,
            top + panelHeight,
            BACKDROP);

        int y = top + PAD;
        for (Line line : lines) {
            HudText.drawScaled(graphics, mc.font, line.text(), MARGIN + PAD, y, line.color(), true);
            y += lineHeight;
        }
    }

    private static List<Line> buildLines() {
        List<Line> lines = new ArrayList<>(7);
        lines.add(new Line(VersionInfo.DISPLAY, COLOR_TITLE));
        lines.add(new Line("Train seed: " + TrainDebugState.seed(), COLOR_BODY));

        boolean onTrain = TrainDebugState.carriagePresent();
        lines.add(new Line("Carriage: "
            + (onTrain ? formatSigned(TrainDebugState.pIdx()) : NONE), COLOR_BODY));

        lines.add(new Line("Cart type: " + fieldOr(onTrain, TrainDebugState.variantId()), COLOR_BODY));
        lines.add(new Line("Content type: " + fieldOr(onTrain, TrainDebugState.contentsId()), COLOR_BODY));
        // Empty is meaningful here rather than unknown: the group draw landed on the parent's own
        // contents, or the parent has no group at all. Either way there is no sub-variant.
        lines.add(new Line("Sub variant: " + fieldOr(onTrain, TrainDebugState.subVariantId()), COLOR_BODY));

        // A "forever" grant (expiry 0) has no countdown to show.
        long expiresAtMs = TrainDebugState.expiresAtMs();
        if (expiresAtMs > 0L) {
            lines.add(new Line("Access expires in "
                + formatRemaining(expiresAtMs - System.currentTimeMillis()), COLOR_EXPIRY));
        }
        return lines;
    }

    /** A field's value, or the placeholder when off-train or the server had nothing to send. */
    private static String fieldOr(boolean onTrain, String value) {
        return onTrain && !value.isEmpty() ? value : NONE;
    }

    private static String formatSigned(int n) {
        return n > 0 ? "+" + n : Integer.toString(n);
    }

    /**
     * Coarse countdown — the panel is refreshed every frame, so a seconds-level read-out would
     * flicker attention for no benefit. Rounds up so a grant never reads "0m" while still live.
     */
    static String formatRemaining(long remainingMs) {
        if (remainingMs <= 0L) {
            return "0m";
        }
        long minutes = (remainingMs + 59_999L) / 60_000L;
        if (minutes < 60L) {
            return minutes + "m";
        }
        long hours = (minutes + 59L) / 60L;
        if (hours < 48L) {
            return hours + "h";
        }
        return String.format(Locale.ROOT, "%dd", (hours + 23L) / 24L);
    }

    private record Line(String text, int color) {}
}
