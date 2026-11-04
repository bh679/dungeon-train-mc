package games.brennan.dungeontrain.client.moty;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * One Mod of the Year ask ({@link ModOfTheYear.Ask}): nominate, or vote for, the Dungeon Train modpack
 * (Modpack of the Year) or the Dungeon Train mod (Mod of the Year).
 *
 * <p>Same flat card as {@link games.brennan.dungeontrain.client.version.UpdatePromptScreen}, so every
 * one-time message looks like it came from the same place.</p>
 *
 * <p>The main button and "Already did" both mark the ask done, so it is never asked again, and the
 * next ask (the mod, once the modpack is done) takes its place straight away. "Not now" and Esc just
 * close, and the next launch asks again while the phase is open.</p>
 *
 * <p>Each ask reports to {@link UiAnalytics} (surface {@code moty_popup}): shown, the main button and
 * whether the link was then opened, "Already did", and "Not now" / Esc.</p>
 */
public final class ModOfTheYearPopupScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String KEY = "gui.dungeontrain.moty.";

    // Flat card geometry — mirrors UpdatePromptScreen so the cards read as one family.
    private static final int CARD_W = 260;
    private static final int PAD = 14;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int GAP_TITLE = 9;
    private static final int GAP_BODY = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;

    private final Screen parent;
    private final ModOfTheYear.Ask ask;
    // Set once a button has reported, so the onClose() that ends every path doesn't also count a "later".
    private boolean answered;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private List<FormattedCharSequence> bodyLines = List.of();
    private int titleY;
    private int bodyY;

    public ModOfTheYearPopupScreen(Screen parent, ModOfTheYear.Ask ask) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        this.ask = ask;
        UiAnalytics.shown(UiAnalytics.SURFACE_MOTY_POPUP, ask.analyticsTarget());
    }

    /** The body text for this ask. */
    private String messageKey() {
        return switch (ask) {
            case NOMINATE_MODPACK -> "message.nominate";
            case NOMINATE_MOD -> "message.nominate_mod";
            case VOTE_MODPACK -> "message.vote";
            case VOTE_MOD -> "message.vote_mod";
        };
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(Component.translatable(KEY + messageKey()), innerWidth);

        int contentH = font.lineHeight + GAP_TITLE
                + bodyLines.size() * LINE_STEP + GAP_BODY
                + BUTTON_H;
        panelW = CARD_W;
        panelH = PAD + contentH + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(16, (height - panelH) / 2);
        centerX = panelX + panelW / 2;

        int cursor = panelY + PAD;
        titleY = cursor;
        cursor += font.lineHeight + GAP_TITLE;
        bodyY = cursor;
        cursor += bodyLines.size() * LINE_STEP + GAP_BODY;

        // One row: the ask on the left, then "already did", then the way out.
        int innerLeft = panelX + PAD;
        int buttonW = (innerWidth - 2 * BUTTON_GAP) / 3;
        addRenderableWidget(Button.builder(
                        Component.translatable(KEY + (ask.voting() ? "button.vote" : "button.nominate")),
                        b -> openContest())
                .bounds(innerLeft, cursor, buttonW, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY + "button.done"), b -> alreadyDid())
                .bounds(innerLeft + buttonW + BUTTON_GAP, cursor, buttonW, BUTTON_H)
                .build());
        int laterX = innerLeft + 2 * (buttonW + BUTTON_GAP);
        addRenderableWidget(Button.builder(Component.translatable(KEY + "button.later"), b -> onClose())
                .bounds(laterX, cursor, innerLeft + innerWidth - laterX, BUTTON_H)
                .build());
    }

    /** The contest page behind vanilla's "open this link?" confirmation; then the next ask, if any. */
    private void openContest() {
        String url = ask.contestUrl();
        String target = ask.analyticsTarget();
        answered = true;
        ClientDisplayConfig.markMotyDone(ask);
        LOGGER.info("ModOfTheYearPopup: {} clicked — opening {}", ask, url);
        UiAnalytics.click(UiAnalytics.SURFACE_MOTY_POPUP, target);
        this.minecraft.setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_MOTY_POPUP, target, yes);
            if (yes) Util.getPlatform().openUri(URI.create(url));
            showNextOrClose();
        }, url, true));
    }

    private void alreadyDid() {
        LOGGER.info("ModOfTheYearPopup: 'Already did' clicked for {}", ask);
        answered = true;
        UiAnalytics.click(UiAnalytics.SURFACE_MOTY_POPUP, ask.analyticsTarget() + ModOfTheYear.DONE_SUFFIX);
        ClientDisplayConfig.markMotyDone(ask);
        showNextOrClose();
    }

    /** Once an ask is done, the next one (the mod, after the modpack) follows on; otherwise back out. */
    private void showNextOrClose() {
        Optional<ModOfTheYear.Ask> next = ModOfTheYearPopupHandler.nextAsk();
        if (next.isPresent()) {
            LOGGER.info("ModOfTheYearPopup: following on with {}", next.get());
            this.minecraft.setScreen(new ModOfTheYearPopupScreen(parent, next.get()));
        } else {
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, BACKDROP_DIM);

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, CARD_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, CARD_BORDER);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, CARD_BORDER);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelH, CARD_BORDER);
        graphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, CARD_BORDER);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, this.title, centerX, titleY, COLOR_TITLE);
        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }
    }

    /** "Not now" and Esc both go back to what was underneath. */
    @Override
    public void onClose() {
        if (!answered) {
            answered = true;
            UiAnalytics.click(UiAnalytics.SURFACE_MOTY_POPUP, ask.analyticsTarget() + ModOfTheYear.LATER_SUFFIX);
        }
        this.minecraft.setScreen(parent);
    }
}
