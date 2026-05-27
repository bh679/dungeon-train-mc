package games.brennan.dungeontrain.client.version;

import games.brennan.dungeontrain.client.VersionInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.net.URI;

/**
 * Label-styled {@link Button} that combines the existing top-left
 * "Dungeon Train v{ver} ({branch})" overlay with the GitHub release-check
 * status. Renders as plain text (no button sprite) so it reads as a status
 * line, but is still a real button so we get keyboard nav, narration and
 * click handling for free.
 *
 * <p>The branch suffix appears only when the build was not made on
 * {@code main} — same dev-mode behaviour the previous {@code VersionMenuOverlay}
 * had. The status suffix is silent on the calm states ({@code LATEST},
 * {@code AHEAD}, {@code CHECKING}) and appears in colour only when something
 * is actionable ({@code UPDATE_AVAILABLE}, {@code ERROR}).</p>
 *
 * <p>The text is recomputed every frame from {@link VersionCheckState} so
 * the async fetch result appears in place without needing to rebuild the
 * title screen.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VersionStatusButton extends Button {

    private static final int HEIGHT = 10;
    private static final int PADDING = 4;
    private static final String MAIN_BRANCH = "main";

    private static final int COLOR_NEUTRAL = 0xFFFFFF;
    private static final int COLOR_UPDATE  = 0xFFCC44;
    private static final int COLOR_ERROR   = 0xAA4444;

    public VersionStatusButton(int x, int y) {
        super(x, y, 0, HEIGHT, Component.empty(),
              b -> handleClick(),
              DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        Component msg = currentMessage();
        setMessage(msg);

        int textWidth = font.width(msg);
        setWidth(textWidth + PADDING);

        int textX = getX() + 2;
        int textY = getY() + (getHeight() - font.lineHeight) / 2;
        int alphaByte = Mth.ceil(this.alpha * 255.0F) << 24;
        int color = alphaByte | (currentColor() & 0x00FFFFFF);

        g.drawString(font, msg, textX, textY, color, true);

        if (this.isHoveredOrFocused() && isClickable()) {
            g.fill(textX, textY + font.lineHeight,
                   textX + textWidth, textY + font.lineHeight + 1, color);
        }
    }

    private static Component currentMessage() {
        StringBuilder prefix = new StringBuilder("Dungeon Train v").append(VersionInfo.VERSION);
        if (!MAIN_BRANCH.equals(VersionInfo.BRANCH)) {
            prefix.append(" (").append(VersionInfo.BRANCH).append(')');
        }
        Component prefixComp = Component.literal(prefix.toString());

        return switch (VersionCheckState.status()) {
            case UPDATE_AVAILABLE -> prefixComp.copy().append(" ")
                .append(Component.translatable("gui.dungeontrain.version.update",
                    nullSafe(VersionCheckState.latestTag())));
            case ERROR -> prefixComp.copy().append(" ")
                .append(Component.translatable("gui.dungeontrain.version.error"));
            case LATEST, AHEAD, CHECKING -> prefixComp;
        };
    }

    private static int currentColor() {
        return switch (VersionCheckState.status()) {
            case UPDATE_AVAILABLE -> COLOR_UPDATE;
            case ERROR            -> COLOR_ERROR;
            case LATEST, AHEAD, CHECKING -> COLOR_NEUTRAL;
        };
    }

    private static boolean isClickable() {
        return VersionCheckState.status() != VersionCheckState.Status.CHECKING;
    }

    private static String nullSafe(String s) {
        return s == null ? "?" : s;
    }

    private static void handleClick() {
        VersionCheckState.Status s = VersionCheckState.status();
        Screen parent = Minecraft.getInstance().screen;
        switch (s) {
            case LATEST, UPDATE_AVAILABLE, AHEAD ->
                openLink(parent, LauncherDetector.getUpdateUrl());
            case ERROR -> VersionCheckState.ensureChecked();
            case CHECKING -> { /* no-op while in flight */ }
        }
    }

    private static void openLink(Screen parent, String url) {
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            Minecraft.getInstance().setScreen(parent);
        }, url, true));
    }
}
