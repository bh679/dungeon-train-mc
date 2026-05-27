package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.List;
import org.slf4j.Logger;

/**
 * Compact "message from the developer" notification anchored to the
 * bottom-right of the screen. Surfaced by
 * {@link DeveloperWelcomePopupHandler} after the player has played at least
 * one world this session AND the title screen has been visible for a short
 * settle delay (handler-side, not screen-side — see that class for the
 * "wait for menu to load" logic).
 *
 * <p>Layout: small landscape panel (~1/6 of screen width, content-fit
 * height) tucked into the bottom-right corner. Row 1 — small avatar + dev
 * name. Row 2 — wrapped message body. Row 3 — Chat on Discord / Maybe later
 * buttons. The panel slides in from off-screen-right with a cubic ease-out
 * when the screen first opens.</p>
 *
 * <p>The Discord button wraps the URL in vanilla's
 * {@link ConfirmLinkScreen} so the player gets the standard "open external
 * link?" confirmation — same contract as
 * {@code TitleScreenLayoutHandler#openDiscord} on the existing title-screen
 * Discord button.</p>
 *
 * <p>Avatar texture is loaded from
 * {@code assets/dungeontrain/textures/gui/developer_avatar.png} when present;
 * falls back to a flat coloured tile with a single letter so the popup
 * looks finished even before the real image lands.</p>
 */
public final class DeveloperWelcomePopupScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DISCORD_URL = "https://discord.gg/jdKAwb6rbW";

    private static final ResourceLocation AVATAR_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "textures/gui/developer_avatar.png");

    // --- Sizing constants (compact landscape panel anchored bottom-right) ---
    /** Minimum panel width — keeps the message readable on small dev windows where {@code width/6} would be too tight. */
    private static final int MIN_PANEL_W = 200;
    /** Panel width as a fraction of screen width — 1/6 of the screen. */
    private static final int PANEL_W_DIVISOR = 6;
    /** Distance from the right/bottom screen edges to the panel. */
    private static final int MARGIN = 10;
    /** Inner padding inside the panel. */
    private static final int PADDING = 6;
    /** Vertical gap between the avatar/author row and the message body. */
    private static final int GAP_AFTER_HEADER = 4;
    /** Vertical gap between the message body and the bottom button row. */
    private static final int GAP_BEFORE_BUTTONS = 5;
    /** Extra pixel between wrapped message lines (on top of {@code font.lineHeight}). */
    private static final int MESSAGE_LINE_SPACING = 1;
    /** Avatar tile size. */
    private static final int AVATAR_SIZE = 24;
    /** Button height — kept tappable while staying within the compact panel. */
    private static final int BUTTON_H = 16;
    /** Horizontal gap between the two bottom buttons. */
    private static final int BUTTON_GAP = 4;
    /** Vertical gap between the primary button row and the "Don't ask again" row. */
    private static final int GAP_BEFORE_OPT_OUT = 3;
    /** "Don't ask again" button is a touch shorter than the primary buttons — visually less prominent. */
    private static final int OPT_OUT_BUTTON_H = 14;

    // --- Slide-in animation ---
    /** How long the slide takes once the popup screen opens. */
    private static final long SLIDE_DURATION_MS = 450L;

    private static final int COLOUR_PANEL_BG = 0xFF1E1E1E;
    private static final int COLOUR_PANEL_BORDER = 0xFF3A3A3A;
    private static final int COLOUR_AVATAR_FALLBACK_BG = 0xFF4A6FA5;
    private static final int COLOUR_AUTHOR = 0xFFFFFFFF;
    private static final int COLOUR_MESSAGE = 0xFFE0E0E0;

    private final Screen parent;
    /** True if the player has seen this popup at least once before — adds the "Don't ask again" button. */
    private final boolean isReturning;

    // Computed in init() once width/height/font are known; reused in render().
    private int panelX;       // resting (final) X
    private int panelY;
    private int panelW;
    private int panelH;
    /** Pre-split message lines, cached so {@code render()} doesn't re-wrap every frame. */
    private List<FormattedCharSequence> messageLines = List.of();
    /** Wall-clock time the popup was first init'd — used to compute slide-in progress. */
    private long openedAtMs;
    /** Button references kept so the slide can update their X each frame for click-hit accuracy. */
    private DarkTintedButton discordButton;
    private Button laterButton;
    /** Only present when {@link #isReturning} is true. */
    private Button dontAskAgainButton;

    public DeveloperWelcomePopupScreen(Screen parent) {
        this(parent, false);
    }

    public DeveloperWelcomePopupScreen(Screen parent, boolean isReturning) {
        super(Component.translatable("gui.dungeontrain.developer_popup.title"));
        this.parent = parent;
        this.isReturning = isReturning;
    }

    @Override
    protected void init() {
        // Width derives from screen size so the popup scales with GUI
        // resolution. Clamped to MIN_PANEL_W to avoid microscopic panels
        // on tiny windows.
        panelW = Math.max(MIN_PANEL_W, this.width / PANEL_W_DIVISOR);

        // Height is content-driven — sum of padding + header row + message
        // lines + buttons.
        int innerW = panelW - 2 * PADDING;
        Component message = Component.translatable("gui.dungeontrain.developer_popup.message");
        messageLines = this.font.split(message, innerW);
        int messageBlockH = messageLines.size() * (this.font.lineHeight + MESSAGE_LINE_SPACING) - MESSAGE_LINE_SPACING;
        if (messageBlockH < 0) messageBlockH = 0;

        int optOutRowH = isReturning ? (GAP_BEFORE_OPT_OUT + OPT_OUT_BUTTON_H) : 0;
        panelH = PADDING
                + AVATAR_SIZE
                + GAP_AFTER_HEADER
                + messageBlockH
                + GAP_BEFORE_BUTTONS
                + BUTTON_H
                + optOutRowH
                + PADDING;

        // Resting position — bottom-right with a margin from both edges.
        panelX = this.width - panelW - MARGIN;
        panelY = this.height - panelH - MARGIN;

        // Primary buttons sit on the row above the opt-out (when present)
        // or on the bottom padding line (when not present).
        int primaryRowY = panelY + panelH - PADDING - BUTTON_H - optOutRowH;
        int buttonW = (innerW - BUTTON_GAP) / 2;
        int leftButtonX = panelX + PADDING;
        int rightButtonX = leftButtonX + buttonW + BUTTON_GAP;

        // Buttons start at their resting X; render() will offset them while
        // the slide-in is still playing.
        discordButton = new DarkTintedButton(
                leftButtonX, primaryRowY, buttonW, BUTTON_H,
                Component.translatable("gui.dungeontrain.developer_popup.discord"),
                b -> openDiscord());
        addRenderableWidget(discordButton);

        laterButton = Button.builder(
                        Component.translatable("gui.dungeontrain.developer_popup.close"),
                        b -> onClose())
                .bounds(rightButtonX, primaryRowY, buttonW, BUTTON_H)
                .build();
        addRenderableWidget(laterButton);

        if (isReturning) {
            // Narrower opt-out button, centred under the primary row so it
            // reads as a tertiary action rather than competing with the
            // primary buttons above.
            int optOutWidth = Math.min(innerW, 140);
            int optOutX = panelX + (panelW - optOutWidth) / 2;
            int optOutY = primaryRowY + BUTTON_H + GAP_BEFORE_OPT_OUT;
            dontAskAgainButton = Button.builder(
                            Component.translatable("gui.dungeontrain.developer_popup.dont_ask_again"),
                            b -> dontAskAgain())
                    .bounds(optOutX, optOutY, optOutWidth, OPT_OUT_BUTTON_H)
                    .build();
            addRenderableWidget(dontAskAgainButton);
        }

        openedAtMs = Util.getMillis();
    }

    /**
     * Slide-in eased X offset added to {@link #panelX}. Cubic ease-out from
     * fully off-screen-right to the resting position over
     * {@link #SLIDE_DURATION_MS}.
     */
    private int slideOffsetX() {
        long elapsed = Util.getMillis() - openedAtMs;
        int offscreenDistance = panelW + MARGIN + 4; // +4 so the border is fully past the edge while waiting
        if (elapsed >= SLIDE_DURATION_MS) {
            return 0;
        }
        float t = (float) elapsed / SLIDE_DURATION_MS;
        float invT = 1.0F - t;
        float eased = 1.0F - invT * invT * invT; // cubic ease-out
        return Math.round(offscreenDistance * (1.0F - eased));
    }

    /** Keep the button widgets' X in sync with the current slide offset so clicks land on what the user sees. */
    private void syncButtonPositions(int currentPanelX) {
        if (discordButton == null || laterButton == null) return;
        int innerW = panelW - 2 * PADDING;
        int buttonW = (innerW - BUTTON_GAP) / 2;
        int leftButtonX = currentPanelX + PADDING;
        int rightButtonX = leftButtonX + buttonW + BUTTON_GAP;
        discordButton.setX(leftButtonX);
        laterButton.setX(rightButtonX);
        if (dontAskAgainButton != null) {
            int optOutX = currentPanelX + (panelW - dontAskAgainButton.getWidth()) / 2;
            dontAskAgainButton.setX(optOutX);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int offset = slideOffsetX();
        int currentPanelX = panelX + offset;
        syncButtonPositions(currentPanelX);

        // Default Screen background draws the menu panorama when not in-game,
        // letting it spin behind our notification. We don't try to render the
        // parent title screen — Minecraft's GuiGraphics batches font
        // rendering and flushes at end-of-frame, which made the title's
        // labels bleed through any panel we drew over them. Cleaner: handler
        // delays the popup-open until the menu has had time to settle, so
        // the user sees the menu fully before the popup screen takes over.
        super.render(graphics, mouseX, mouseY, partialTick);

        // Panel body + 1px border.
        graphics.fill(currentPanelX - 1, panelY - 1, currentPanelX + panelW + 1, panelY + panelH + 1, COLOUR_PANEL_BORDER);
        graphics.fill(currentPanelX, panelY, currentPanelX + panelW, panelY + panelH, COLOUR_PANEL_BG);

        Font font = this.font;
        int contentX = currentPanelX + PADDING;
        int contentTopY = panelY + PADDING;

        // Row 1 — avatar + author label.
        renderAvatar(graphics, contentX, contentTopY);
        int authorX = contentX + AVATAR_SIZE + PADDING;
        int authorY = contentTopY + (AVATAR_SIZE - font.lineHeight) / 2;
        Component author = Component.translatable("gui.dungeontrain.developer_popup.author");
        graphics.drawString(font, author, authorX, authorY, COLOUR_AUTHOR, false);

        // Row 2 — wrapped message body, each line horizontally centred
        // within the panel. Compute X per line so wrapping that produces
        // uneven line widths still reads as visually centred.
        int messageTopY = contentTopY + AVATAR_SIZE + GAP_AFTER_HEADER;
        int messageCenterX = currentPanelX + panelW / 2;
        for (int i = 0; i < messageLines.size(); i++) {
            FormattedCharSequence line = messageLines.get(i);
            int lineWidth = font.width(line);
            int x = messageCenterX - lineWidth / 2;
            int y = messageTopY + i * (font.lineHeight + MESSAGE_LINE_SPACING);
            graphics.drawString(font, line, x, y, COLOUR_MESSAGE, false);
        }

        // Row 3 — buttons were already drawn by super.render() via the
        // widget pipeline; nothing extra to do here.
    }

    private void renderAvatar(GuiGraphics graphics, int x, int y) {
        if (Minecraft.getInstance().getResourceManager().getResource(AVATAR_TEXTURE).isPresent()) {
            graphics.blit(AVATAR_TEXTURE, x, y, AVATAR_SIZE, AVATAR_SIZE,
                    0.0F, 0.0F, AVATAR_SIZE, AVATAR_SIZE,
                    AVATAR_SIZE, AVATAR_SIZE);
        } else {
            graphics.fill(x, y, x + AVATAR_SIZE, y + AVATAR_SIZE, COLOUR_AVATAR_FALLBACK_BG);
            String initial = "B";
            int letterX = x + AVATAR_SIZE / 2;
            int letterY = y + (AVATAR_SIZE - this.font.lineHeight) / 2;
            graphics.drawCenteredString(this.font, initial, letterX, letterY, 0xFFFFFFFF);
        }
    }

    private void openDiscord() {
        LOGGER.info("DeveloperWelcomePopup: Discord button clicked — opening ConfirmLinkScreen");
        // Return to the title screen after the link confirmation, not back
        // into the popup. Clicking Discord means "I'm done with the message".
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(DISCORD_URL));
            }
            Minecraft.getInstance().setScreen(parent);
        }, DISCORD_URL, true));
    }

    private void dontAskAgain() {
        LOGGER.info("DeveloperWelcomePopup: 'Don't ask again' clicked — persisting opt-out");
        // Persist to dungeontrain-client.toml so future sessions skip the
        // popup entirely. The handler checks this flag before scheduling
        // any open.
        ClientDisplayConfig.setDeveloperPopupOptedOut(true);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
