package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.narrative.PriorityBookMessage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * "Boosting your own book costs 10 boosts...": the confirmation before the star spends a whole
 * Priority Book on the reader's own book. Somebody else's book never asks; a boost there is one.
 *
 * <p>Drawn inside the open book rather than as its own screen, so the book stays open and the vote
 * page keeps its state (swapping screens fires {@code Closing}, which resets it). While it is up,
 * {@link BookVoteClientEvents} hands it every click and key first, and it swallows them all: nothing
 * under it can be clicked. Do it spends; Cancel, Esc or a click outside the panel backs out.
 * "Don't show this again" is saved only on Do it, to {@link ClientDisplayConfig}.</p>
 *
 * <p>Hand-drawn with vanilla's button and checkbox sprites rather than real widgets: widgets added to
 * the book screen would outlive the dialog and sit among the book's own buttons.</p>
 */
final class BookBoostConfirm {

    // Vanilla tooltip palette, as FreePlayConfirmScreen.
    private static final int FRAME_BG = 0xF0100010;
    private static final int FRAME_BORDER_TOP = 0x505000FF;
    private static final int FRAME_BORDER_BOTTOM = 0x5028007F;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_BUTTON_TEXT = 0xFFFFFFFF;
    private static final int COLOR_BUTTON_TEXT_HOVER = 0xFFFFFFA0;

    private static final int MAX_PANEL_W = 200;
    private static final int SCREEN_MARGIN = 10;
    private static final int PADDING = 10;
    private static final int LINE_GAP = 1;
    private static final int SECTION_GAP = 8;
    private static final int CHECKBOX_SIZE = 17;
    private static final int CHECKBOX_LABEL_GAP = 4;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 4;
    /** Above the page's own drawing and its tooltips. */
    private static final float Z = 400F;

    private static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation BUTTON_HIGHLIGHTED =
        ResourceLocation.withDefaultNamespace("widget/button_highlighted");
    private static final ResourceLocation CHECKBOX = ResourceLocation.withDefaultNamespace("widget/checkbox");
    private static final ResourceLocation CHECKBOX_HIGHLIGHTED =
        ResourceLocation.withDefaultNamespace("widget/checkbox_highlighted");
    private static final ResourceLocation CHECKBOX_SELECTED =
        ResourceLocation.withDefaultNamespace("widget/checkbox_selected");
    private static final ResourceLocation CHECKBOX_SELECTED_HIGHLIGHTED =
        ResourceLocation.withDefaultNamespace("widget/checkbox_selected_highlighted");

    /** What Do it runs; null while the dialog is closed. */
    private static Runnable onConfirm = null;
    private static boolean dontShow = false;
    // Last frame's layout; panelW 0 = not drawn yet, so clicks only land on what was seen.
    private static int panelX, panelY, panelW, panelH;
    private static int checkY, buttonsY, buttonW;

    private BookBoostConfirm() {}

    /** Ask before {@code spend} runs, or run it at once when the player opted out of asking. */
    static void ask(Runnable spend) {
        if (ClientDisplayConfig.isOwnBookBoostConfirmOptedOut()) {
            spend.run();
            return;
        }
        onConfirm = spend;
        dontShow = false;
        panelW = 0;
    }

    static boolean isOpen() {
        return onConfirm != null;
    }

    static void close() {
        onConfirm = null;
        dontShow = false;
        panelW = 0;
    }

    static void render(GuiGraphics gfx, Font font, int screenW, int screenH, int mouseX, int mouseY) {
        if (!isOpen()) return;
        int w = Math.min(MAX_PANEL_W, screenW - 2 * SCREEN_MARGIN);
        int innerW = w - 2 * PADDING;
        List<FormattedCharSequence> body = font.split(PriorityBookMessage.ownConfirmBody(), innerW);
        int lh = font.lineHeight;
        int bodyH = body.size() * (lh + LINE_GAP) - LINE_GAP;
        int h = PADDING + bodyH + SECTION_GAP + CHECKBOX_SIZE + SECTION_GAP + BUTTON_H + PADDING;

        panelW = w;
        panelH = h;
        panelX = (screenW - w) / 2;
        panelY = Math.max(SCREEN_MARGIN, (screenH - h) / 2);
        checkY = panelY + PADDING + bodyH + SECTION_GAP;
        buttonsY = checkY + CHECKBOX_SIZE + SECTION_GAP;
        buttonW = (innerW - BUTTON_GAP) / 2;

        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, Z);
        drawFrame(gfx, panelX, panelY, panelX + w, panelY + h);

        int cx = panelX + w / 2;
        int y = panelY + PADDING;
        for (FormattedCharSequence line : body) {
            gfx.drawString(font, line, cx - font.width(line) / 2, y, COLOR_BODY, true);
            y += lh + LINE_GAP;
        }

        int boxX = panelX + PADDING;
        boolean boxHover = inCheckbox(font, mouseX, mouseY);
        gfx.blitSprite(dontShow
            ? (boxHover ? CHECKBOX_SELECTED_HIGHLIGHTED : CHECKBOX_SELECTED)
            : (boxHover ? CHECKBOX_HIGHLIGHTED : CHECKBOX), boxX, checkY, CHECKBOX_SIZE, CHECKBOX_SIZE);
        gfx.drawString(font, PriorityBookMessage.ownConfirmDontShow(),
            boxX + CHECKBOX_SIZE + CHECKBOX_LABEL_GAP, checkY + (CHECKBOX_SIZE - 8) / 2, COLOR_BODY, true);

        drawButton(gfx, font, PriorityBookMessage.ownConfirmDoIt(), doItX(), mouseX, mouseY);
        drawButton(gfx, font, PriorityBookMessage.ownConfirmCancel(), cancelX(), mouseX, mouseY);
        gfx.pose().popPose();
    }

    /**
     * A left click while the dialog is up. Always consumed: only the panel's controls do anything,
     * and a click outside it backs out. Returns whether a control was hit, for the click sound.
     */
    static boolean click(Font font, int mx, int my) {
        if (!isOpen() || panelW == 0) return false;
        if (inCheckbox(font, mx, my)) {
            dontShow = !dontShow;
            return true;
        }
        if (inButton(doItX(), mx, my)) {
            confirm();
            return true;
        }
        boolean cancel = inButton(cancelX(), mx, my);
        close();
        return cancel;
    }

    /** A key while the dialog is up: Esc cancels, Enter does it. Every key is swallowed. */
    static void key(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
        }
    }

    /** Do it: save the opt-out if ticked, close, then spend (closed first, so a spend that asks again could). */
    private static void confirm() {
        Runnable spend = onConfirm;
        if (spend == null) return;
        if (dontShow) ClientDisplayConfig.setOwnBookBoostConfirmOptedOut(true);
        close();
        spend.run();
    }

    private static int doItX() {
        return panelX + PADDING;
    }

    private static int cancelX() {
        return panelX + PADDING + buttonW + BUTTON_GAP;
    }

    private static void drawButton(GuiGraphics gfx, Font font, Component label, int x, int mouseX, int mouseY) {
        boolean hover = inButton(x, mouseX, mouseY);
        gfx.blitSprite(hover ? BUTTON_HIGHLIGHTED : BUTTON, x, buttonsY, buttonW, BUTTON_H);
        gfx.drawCenteredString(font, label, x + buttonW / 2, buttonsY + (BUTTON_H - 8) / 2,
            hover ? COLOR_BUTTON_TEXT_HOVER : COLOR_BUTTON_TEXT);
    }

    private static boolean inButton(int x, int mx, int my) {
        return panelW > 0 && mx >= x && mx < x + buttonW && my >= buttonsY && my < buttonsY + BUTTON_H;
    }

    /** The box and its label are one target, as on a vanilla checkbox. */
    private static boolean inCheckbox(Font font, int mx, int my) {
        int x = panelX + PADDING;
        int w = CHECKBOX_SIZE + CHECKBOX_LABEL_GAP + font.width(PriorityBookMessage.ownConfirmDontShow());
        return panelW > 0 && mx >= x && mx < x + w && my >= checkY && my < checkY + CHECKBOX_SIZE;
    }

    /** Vanilla tooltip-style frame: dark fill + purple gradient border. */
    private static void drawFrame(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, FRAME_BG);
        g.fill(x0, y0, x1, y0 + 1, FRAME_BORDER_TOP);
        g.fill(x0, y1 - 1, x1, y1, FRAME_BORDER_BOTTOM);
        g.fillGradient(x0, y0 + 1, x0 + 1, y1 - 1, FRAME_BORDER_TOP, FRAME_BORDER_BOTTOM);
        g.fillGradient(x1 - 1, y0 + 1, x1, y1 - 1, FRAME_BORDER_TOP, FRAME_BORDER_BOTTOM);
    }
}
