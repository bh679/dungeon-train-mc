package games.brennan.dungeontrain.client.moty;

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
import org.slf4j.Logger;

import java.net.URI;
import java.util.List;

/**
 * The Mod of the Year ask: a compact panel from the developer that slides in at the bottom-right,
 * the same shape as {@code DeveloperWelcomePopupScreen}. It asks a player who recommended Dungeon
 * Train to nominate the modpack ({@link ModOfTheYear.Phase#NOMINATE}) or vote for it
 * ({@link ModOfTheYear.Phase#VOTE}).
 *
 * <p>The main button and "Already did" both mark the phase done, so it is never asked again;
 * "Not now" just closes, and the next launch asks again while the phase is open. Nominating also
 * copies the modpack link to the clipboard, ready to paste into the form.</p>
 */
public final class ModOfTheYearPopupScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation AVATAR_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "textures/gui/developer_avatar.png");

    private static final int MIN_PANEL_W = 200;
    private static final int PANEL_W_DIVISOR = 6;
    private static final int MARGIN = 10;
    private static final int PADDING = 6;
    private static final int GAP_AFTER_HEADER = 4;
    private static final int GAP_BEFORE_BUTTONS = 5;
    private static final int MESSAGE_LINE_SPACING = 1;
    private static final int AVATAR_SIZE = 24;
    private static final int BUTTON_H = 16;
    private static final int BUTTON_GAP = 4;
    private static final int GAP_BEFORE_LATER = 3;
    /** "Not now" is a touch shorter than the primary buttons — the least prominent choice. */
    private static final int LATER_BUTTON_H = 14;
    private static final int LATER_BUTTON_MAX_W = 140;

    private static final long SLIDE_DURATION_MS = 450L;

    private static final int COLOUR_PANEL_BG = 0xFF1E1E1E;
    private static final int COLOUR_PANEL_BORDER = 0xFF3A3A3A;
    private static final int COLOUR_AVATAR_FALLBACK_BG = 0xFF4A6FA5;
    private static final int COLOUR_AUTHOR = 0xFFFFFFFF;
    private static final int COLOUR_MESSAGE = 0xFFE0E0E0;

    private final Screen parent;
    private final ModOfTheYear.Phase phase;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private List<FormattedCharSequence> messageLines = List.of();
    private long openedAtMs;
    private DarkTintedButton actionButton;
    private Button doneButton;
    private Button laterButton;

    public ModOfTheYearPopupScreen(Screen parent, ModOfTheYear.Phase phase) {
        super(Component.translatable("gui.dungeontrain.moty.title"));
        this.parent = parent;
        this.phase = phase;
    }

    private boolean voting() {
        return phase == ModOfTheYear.Phase.VOTE;
    }

    @Override
    protected void init() {
        panelW = Math.max(MIN_PANEL_W, this.width / PANEL_W_DIVISOR);
        int innerW = panelW - 2 * PADDING;
        Component message = Component.translatable(voting()
                ? "gui.dungeontrain.moty.message.vote"
                : "gui.dungeontrain.moty.message.nominate");
        messageLines = this.font.split(message, innerW);
        int messageBlockH = Math.max(0,
                messageLines.size() * (this.font.lineHeight + MESSAGE_LINE_SPACING) - MESSAGE_LINE_SPACING);

        panelH = PADDING + AVATAR_SIZE + GAP_AFTER_HEADER + messageBlockH + GAP_BEFORE_BUTTONS
                + BUTTON_H + GAP_BEFORE_LATER + LATER_BUTTON_H + PADDING;
        panelX = this.width - panelW - MARGIN;
        panelY = this.height - panelH - MARGIN;

        int primaryRowY = panelY + panelH - PADDING - LATER_BUTTON_H - GAP_BEFORE_LATER - BUTTON_H;
        int buttonW = (innerW - BUTTON_GAP) / 2;
        int leftButtonX = panelX + PADDING;

        actionButton = new DarkTintedButton(
                leftButtonX, primaryRowY, buttonW, BUTTON_H,
                Component.translatable(voting()
                        ? "gui.dungeontrain.moty.button.vote"
                        : "gui.dungeontrain.moty.button.nominate"),
                b -> openContest());
        addRenderableWidget(actionButton);

        doneButton = Button.builder(Component.translatable("gui.dungeontrain.moty.button.done"), b -> alreadyDid())
                .bounds(leftButtonX + buttonW + BUTTON_GAP, primaryRowY, buttonW, BUTTON_H)
                .build();
        addRenderableWidget(doneButton);

        int laterW = Math.min(innerW, LATER_BUTTON_MAX_W);
        laterButton = Button.builder(Component.translatable("gui.dungeontrain.moty.button.later"), b -> onClose())
                .bounds(panelX + (panelW - laterW) / 2, primaryRowY + BUTTON_H + GAP_BEFORE_LATER,
                        laterW, LATER_BUTTON_H)
                .build();
        addRenderableWidget(laterButton);

        openedAtMs = Util.getMillis();
    }

    /** Cubic ease-out from off-screen-right to the resting position. */
    private int slideOffsetX() {
        long elapsed = Util.getMillis() - openedAtMs;
        if (elapsed >= SLIDE_DURATION_MS) return 0;
        int offscreenDistance = panelW + MARGIN + 4;
        float invT = 1.0F - (float) elapsed / SLIDE_DURATION_MS;
        return Math.round(offscreenDistance * invT * invT * invT);
    }

    /** Keep the buttons under the sliding panel so clicks land on what the player sees. */
    private void syncButtonPositions(int currentPanelX) {
        int innerW = panelW - 2 * PADDING;
        int buttonW = (innerW - BUTTON_GAP) / 2;
        actionButton.setX(currentPanelX + PADDING);
        doneButton.setX(currentPanelX + PADDING + buttonW + BUTTON_GAP);
        laterButton.setX(currentPanelX + (panelW - laterButton.getWidth()) / 2);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int currentPanelX = panelX + slideOffsetX();
        syncButtonPositions(currentPanelX);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.fill(currentPanelX - 1, panelY - 1, currentPanelX + panelW + 1, panelY + panelH + 1, COLOUR_PANEL_BORDER);
        graphics.fill(currentPanelX, panelY, currentPanelX + panelW, panelY + panelH, COLOUR_PANEL_BG);

        Font font = this.font;
        int contentX = currentPanelX + PADDING;
        int contentTopY = panelY + PADDING;
        renderAvatar(graphics, contentX, contentTopY);
        graphics.drawString(font, Component.translatable("gui.dungeontrain.developer_popup.author"),
                contentX + AVATAR_SIZE + PADDING, contentTopY + (AVATAR_SIZE - font.lineHeight) / 2,
                COLOUR_AUTHOR, false);

        int messageTopY = contentTopY + AVATAR_SIZE + GAP_AFTER_HEADER;
        int messageCenterX = currentPanelX + panelW / 2;
        for (int i = 0; i < messageLines.size(); i++) {
            FormattedCharSequence line = messageLines.get(i);
            graphics.drawString(font, line, messageCenterX - font.width(line) / 2,
                    messageTopY + i * (font.lineHeight + MESSAGE_LINE_SPACING), COLOUR_MESSAGE, false);
        }
    }

    private void renderAvatar(GuiGraphics graphics, int x, int y) {
        if (Minecraft.getInstance().getResourceManager().getResource(AVATAR_TEXTURE).isPresent()) {
            graphics.blit(AVATAR_TEXTURE, x, y, AVATAR_SIZE, AVATAR_SIZE,
                    0.0F, 0.0F, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE);
        } else {
            graphics.fill(x, y, x + AVATAR_SIZE, y + AVATAR_SIZE, COLOUR_AVATAR_FALLBACK_BG);
            graphics.drawCenteredString(this.font, "B", x + AVATAR_SIZE / 2,
                    y + (AVATAR_SIZE - this.font.lineHeight) / 2, 0xFFFFFFFF);
        }
    }

    private void openContest() {
        String url = voting() ? ModOfTheYear.VOTE_URL : ModOfTheYear.NOMINATION_URL;
        Minecraft mc = Minecraft.getInstance();
        if (!voting()) {
            mc.keyboardHandler.setClipboard(ModOfTheYear.MODPACK_URL);
        }
        markDone();
        LOGGER.info("ModOfTheYearPopup: {} clicked — opening {}", phase, url);
        mc.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) Util.getPlatform().openUri(URI.create(url));
            mc.setScreen(parent);
        }, url, true));
    }

    private void alreadyDid() {
        LOGGER.info("ModOfTheYearPopup: 'Already did' clicked for {}", phase);
        markDone();
        onClose();
    }

    private void markDone() {
        if (voting()) {
            ClientDisplayConfig.markMotyVoted();
        } else {
            ClientDisplayConfig.markMotyNominated();
        }
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
