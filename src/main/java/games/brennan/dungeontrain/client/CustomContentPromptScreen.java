package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.CustomContentPreference;
import games.brennan.dungeontrain.net.CustomContentChoicePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.Consumer;

/**
 * "Custom train content" — shown once per world when Train Editor content (the
 * player's own edits or an imported dtpack) is in play.
 *
 * <p>Two modes, differing only in where the answer goes. <b>Pre-world</b> is the
 * one players normally see: {@link CustomContentGate} puts it up when they press
 * New World or reboard, so the answer is recorded before the world is created and
 * "disable" means a world that never loads the content. <b>Join-time</b> is the
 * fallback for worlds that reach login unanswered — a multiplayer server, or a
 * world created through the vanilla world list — where the answer goes back over
 * the network to {@code CustomContentPromptEvents}.</p>
 *
 * <p>Continue plays the world with that content, which keeps the run in Free
 * Play. Disable Custom Changes turns the content off for this world from here
 * on, so the bundled game runs and stats count. A "Remember decision" checkbox
 * persists the answer to {@link ClientDisplayConfig} and is changeable later in
 * Options → Dungeon Train.</p>
 *
 * <p>Drawn with the same vanilla tooltip frame as {@link FreePlayConfirmScreen}
 * so the two prompts read as one family of UI.</p>
 */
public final class CustomContentPromptScreen extends Screen {

    // Vanilla tooltip palette — kept in step with FreePlayConfirmScreen.
    private static final int FRAME_BG = 0xF0100010;
    private static final int FRAME_BORDER_TOP = 0x505000FF;
    private static final int FRAME_BORDER_BOTTOM = 0x5028007F;

    private static final int TITLE_TEAL = 0xFF5BC8C2;
    private static final int COLOUR_BODY = 0xFFE0E0E0;
    private static final int COLOUR_CONSEQ = 0xFFB8B8B8;
    private static final int COLOUR_PACKAGES = 0xFF7E7E8C;
    private static final int COLOUR_SEPARATOR = 0x40FFFFFF;
    /**
     * Ring drawn just outside the "Remember decision" box. Vanilla's checkbox sprite has a dim
     * grey border that all but disappears against this panel's near-black fill, and the checkbox
     * is the one control here whose state the player has to read at a glance.
     */
    private static final int COLOUR_CHECKBOX_OUTLINE = 0xFFD0D0D8;
    /** Tick drawn over the box when it's ticked — vanilla's own is a muted grey at this size. */
    private static final int COLOUR_CHECKBOX_TICK = 0xFFFFFFFF;
    /** U+2713. Already proven to render in this game by the book-vote "Reported" label. */
    private static final String TICK = "\u2713";

    private static final int MAX_PANEL_W = 240;
    private static final int PADDING = 12;
    private static final int LINE_GAP = 1;
    private static final int SECTION_GAP = 7;
    private static final int TITLE_SEP_GAP = 6;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 4;
    private static final int CHECKBOX_H = 20;

    private final String packages;
    /**
     * Pre-world mode: where to send the answer, and where to go if the player backs out. Both null
     * in join-time mode, where the answer goes to the server and there is nothing to back out of.
     */
    private final Consumer<Boolean> onAnswer;
    private final Screen parent;
    private Checkbox rememberBox;
    private boolean responded = false;

    // Layout, computed in init() and reused by render().
    private int panelX, panelY, panelW, panelH;
    private int titleRelY, bodyRelY, keepRelY, disableRelY, packagesRelY;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> keepLines = List.of();
    private List<FormattedCharSequence> disableLines = List.of();
    private List<FormattedCharSequence> packageLines = List.of();

    /** Join-time: the world is already running, and the answer goes back over the network. */
    public CustomContentPromptScreen(String packages) {
        this(packages, null, null);
    }

    /**
     * Pre-world ({@link CustomContentGate}): no world exists yet, so the answer goes to
     * {@code onAnswer} — which records it for the world about to be created and then starts it —
     * and backing out returns to {@code parent} without starting anything.
     */
    public CustomContentPromptScreen(String packages, Screen parent, Consumer<Boolean> onAnswer) {
        super(Component.translatable("gui.dungeontrain.custom_content.title"));
        this.packages = packages;
        this.parent = parent;
        this.onAnswer = onAnswer;
    }

    @Override
    protected void init() {
        panelW = Math.min(MAX_PANEL_W, this.width - 40);
        int innerW = panelW - 2 * PADDING;
        int lh = this.font.lineHeight;

        bodyLines = this.font.split(Component.translatable("gui.dungeontrain.custom_content.body"), innerW);
        keepLines = this.font.split(Component.translatable("gui.dungeontrain.custom_content.keep"), innerW);
        disableLines = this.font.split(Component.translatable("gui.dungeontrain.custom_content.disable"), innerW);
        packageLines = packages.isBlank()
            ? List.of()
            : this.font.split(Component.translatable("gui.dungeontrain.custom_content.packages", packages), innerW);

        int y = PADDING;
        titleRelY = y;    y += lh + TITLE_SEP_GAP;
        bodyRelY = y;     y += bodyLines.size() * (lh + LINE_GAP) + SECTION_GAP;
        keepRelY = y;     y += keepLines.size() * (lh + LINE_GAP);
        disableRelY = y;  y += disableLines.size() * (lh + LINE_GAP) + SECTION_GAP;
        packagesRelY = y; y += packageLines.size() * (lh + LINE_GAP);
        if (!packageLines.isEmpty()) y += SECTION_GAP;
        int checkboxRelY = y; y += CHECKBOX_H + SECTION_GAP;
        int continueRelY = y; y += BUTTON_H + BUTTON_GAP;
        int disableBtnRelY = y; y += BUTTON_H + PADDING;
        panelH = y;

        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        rememberBox = Checkbox.builder(
                Component.translatable("gui.dungeontrain.custom_content.remember"), this.font)
            .pos(panelX + PADDING, panelY + checkboxRelY)
            .selected(!ClientDisplayConfig.getCustomContentPreference().asks())
            .build();
        addRenderableWidget(rememberBox);

        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.custom_content.continue"), b -> respond(true))
            .bounds(panelX + PADDING, panelY + continueRelY, innerW, BUTTON_H).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.custom_content.disable_button"), b -> respond(false))
            .bounds(panelX + PADDING, panelY + disableBtnRelY, innerW, BUTTON_H).build());
    }

    private void respond(boolean keepContent) {
        if (responded) return;
        responded = true;
        if (rememberBox != null && rememberBox.selected()) {
            ClientDisplayConfig.setCustomContentPreference(
                keepContent ? CustomContentPreference.CONTINUE : CustomContentPreference.DISABLE);
        }
        if (onAnswer != null) {
            // Pre-world: the callback records the answer for the world about to be created and
            // starts it, which replaces this screen. Nothing to send and nothing to close.
            onAnswer.accept(keepContent);
            return;
        }
        CustomContentPromptClient.answered();
        DungeonTrainNet.sendToServer(new CustomContentChoicePacket(keepContent));
        onClose();
    }

    /**
     * What dismissing means depends on which question this is.
     *
     * <p><b>Join-time:</b> ESC answers "continue" rather than cancelling. There is nothing to back
     * out of — the world is already running with the content — so dismissing must mean "leave
     * things as they are", not silently disable someone's builds.</p>
     *
     * <p><b>Pre-world:</b> nothing has started, so dismissing backs out of starting it. No answer
     * is recorded and the player lands back where they were; pressing New World again asks again.
     * Answering "continue" for them here would be the one reading they did not choose — it would
     * start a Free Play run off an ESC keypress.</p>
     */
    @Override
    public void onClose() {
        if (onAnswer != null) {
            this.minecraft.setScreen(parent);
            return;
        }
        if (!responded) {
            responded = true;
            CustomContentPromptClient.answered();
            DungeonTrainNet.sendToServer(new CustomContentChoicePacket(true));
        }
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        drawFrame(g, panelX, panelY, panelX + panelW, panelY + panelH);

        int cx = panelX + panelW / 2;
        int lh = this.font.lineHeight;

        g.drawCenteredString(this.font, this.title, cx, panelY + titleRelY, TITLE_TEAL);
        int sepY = panelY + titleRelY + lh + 2;
        g.fill(panelX + 10, sepY, panelX + panelW - 10, sepY + 1, COLOUR_SEPARATOR);

        drawLines(g, bodyLines, cx, panelY + bodyRelY, COLOUR_BODY, lh);
        drawLines(g, keepLines, cx, panelY + keepRelY, COLOUR_CONSEQ, lh);
        drawLines(g, disableLines, cx, panelY + disableRelY, COLOUR_CONSEQ, lh);
        drawLines(g, packageLines, cx, panelY + packagesRelY, COLOUR_PACKAGES, lh);

        // Sits one pixel OUTSIDE the sprite rather than on top of it: GuiGraphics flushes textured
        // quads after flat fills, so anything drawn over the sprite's own footprint would end up
        // beneath it however late we draw.
        if (rememberBox != null) {
            int box = rememberBox.getHeight();
            g.renderOutline(rememberBox.getX() - 1, rememberBox.getY() - 1,
                box + 2, box + 2, COLOUR_CHECKBOX_OUTLINE);
            if (rememberBox.selected()) {
                // Text, not a fill: glyphs are drawn over widget sprites (the same reason item
                // stack counts sit on top of item icons), so this lands above vanilla's muted tick
                // instead of under it.
                g.drawCenteredString(this.font, TICK,
                    rememberBox.getX() + box / 2,
                    rememberBox.getY() + (box - lh) / 2 + 1,
                    COLOUR_CHECKBOX_TICK);
            }
        }
    }

    private void drawLines(GuiGraphics g, List<FormattedCharSequence> lines, int cx, int y, int colour, int lh) {
        for (FormattedCharSequence line : lines) {
            g.drawCenteredString(this.font, line, cx, y, colour);
            y += lh + LINE_GAP;
        }
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
