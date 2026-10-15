package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveTakeoverClient.Holder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Asked when a Live Eye goes on while someone else is live: who holds the channel, for how long, and
 * whether to take it from them.
 *
 * <p>Inside the holder's protected first minutes the choice is <b>Request takeover</b> — the audience
 * votes — and past them <b>Take over</b>, the wearer's own call; the button changes over the moment the
 * counter passes the line. <b>Stay a viewer</b> gives the Live Eye up: it becomes a TV. Esc is the same
 * as Stay a viewer. Requesting closes the card: the vote's countdown shows over the live box
 * ({@link LiveHeadViewer}, {@link LiveTakeoverFlow}). No dimming or blur: the wearer is watching the
 * feed behind it.</p>
 */
public final class TakeoverRequestScreen extends Screen {

    private static final String KEY = "screen.dungeontrain.live_takeover.";

    private static final int CARD_W = 300;
    private static final int PAD = 12;
    private static final int LINE_STEP = 11;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int GAP_TITLE = 8;
    private static final int GAP_BODY = 10;

    private static final int CARD_BG = 0xE81A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;

    private final Holder holder;
    private final long readAtMs;
    /** The mode the buttons were built for — rebuilt when the counter crosses the protected minutes. */
    private boolean builtForVote;
    /** Set once the player picked: closing after that is not a "Stay a viewer". */
    private boolean decided;

    private int panelX;
    private int panelY;
    private int panelH;
    private int centerX;
    private List<FormattedCharSequence> bodyLines = List.of();

    public TakeoverRequestScreen(Holder holder, long readAtMs) {
        super(Component.translatable(KEY + "title"));
        this.holder = holder;
        this.readAtMs = readAtMs;
    }

    private long elapsedMs() {
        return Math.max(0, System.currentTimeMillis() - readAtMs);
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(body(), innerWidth);
        int contentH = font.lineHeight + GAP_TITLE + bodyLines.size() * LINE_STEP + GAP_BODY + BUTTON_H;
        panelH = PAD + contentH + PAD;
        panelX = (width - CARD_W) / 2;
        panelY = Math.max(16, (height - panelH) / 2);
        centerX = panelX + CARD_W / 2;
        builtForVote = holder.voteNeededAfter(elapsedMs());
        int buttonsY = panelY + PAD + font.lineHeight + GAP_TITLE + bodyLines.size() * LINE_STEP + GAP_BODY;
        int innerLeft = panelX + PAD;
        int buttonW = (innerWidth - BUTTON_GAP) / 2;
        Component take = Component.translatable(KEY + (builtForVote ? "request" : "take_over"));
        Button takeButton = Button.builder(take, b -> takeOver())
            .bounds(innerLeft, buttonsY, buttonW, BUTTON_H)
            .tooltip(Tooltip.create(Component.translatable(KEY + (!builtForVote ? "take_over.tooltip"
                : LiveTakeoverFlow.canRequest() ? "request.tooltip" : "request.used"), holder.holder())))
            .build();
        // One request per Live Eye: once asked, only the holder's protected minutes running out reopen it.
        takeButton.active = !builtForVote || LiveTakeoverFlow.canRequest();
        addRenderableWidget(takeButton);
        addRenderableWidget(Button.builder(Component.translatable(KEY + "stay_viewer"), b -> stayViewer())
            .bounds(innerLeft + buttonW + BUTTON_GAP, buttonsY, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
            .tooltip(Tooltip.create(Component.translatable(KEY + "stay_viewer.tooltip")))
            .build());
    }

    /** The holder line, then what the main button will do. */
    private Component body() {
        Component line = LiveTakeoverText.holderLine(holder.holder(), holder.tenureMs() + elapsedMs(), holder.streamCount());
        String note = holder.voteNeededAfter(elapsedMs()) ? "note.vote" : "note.decide";
        return Component.empty().append(line).append("\n\n").append(Component.translatable(KEY + note, holder.holder()));
    }

    @Override
    public void tick() {
        super.tick();
        // The counter moves; the button changes over when the protected minutes run out.
        if (holder.voteNeededAfter(elapsedMs()) != builtForVote) {
            rebuildWidgets();
        } else {
            bodyLines = font.split(body(), CARD_W - 2 * PAD);
        }
    }

    private void takeOver() {
        decided = true;
        this.minecraft.setScreen(null);
        if (holder.voteNeededAfter(elapsedMs())) {
            LiveTakeoverFlow.request();
        } else {
            LiveTakeoverFlow.takeOver();
        }
    }

    private void stayViewer() {
        decided = true;
        LiveTakeoverFlow.stayViewer();
        this.minecraft.setScreen(null);
    }

    /** Called by the flow when the vote is over (or the stream ended): close without choosing. */
    void dismiss() {
        decided = true;
        if (this.minecraft != null && this.minecraft.screen == this) this.minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int right = panelX + CARD_W;
        int bottom = panelY + panelH;
        graphics.fill(panelX, panelY, right, bottom, CARD_BG);
        graphics.fill(panelX, panelY, right, panelY + 1, CARD_BORDER);
        graphics.fill(panelX, bottom - 1, right, bottom, CARD_BORDER);
        graphics.fill(panelX, panelY, panelX + 1, bottom, CARD_BORDER);
        graphics.fill(right - 1, panelY, right, bottom, CARD_BORDER);
        super.render(graphics, mouseX, mouseY, partialTick);
        int y = panelY + PAD;
        graphics.drawCenteredString(font, this.title, centerX, y, COLOR_TITLE);
        y += font.lineHeight + GAP_TITLE;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }
    }

    /** No blur: the feed plays on behind the card. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Esc (or any close without a choice) is Stay a viewer. */
    @Override
    public void onClose() {
        if (!decided) {
            decided = true;
            LiveTakeoverFlow.stayViewer();
        }
        this.minecraft.setScreen(null);
    }
}
