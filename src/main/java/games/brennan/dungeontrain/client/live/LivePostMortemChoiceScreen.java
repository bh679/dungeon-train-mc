package games.brennan.dungeontrain.client.live;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * Between the death moment and the first death page, for a streamer who died live: end the stream,
 * or carry on streaming through the death screens ({@link LivePostMortem}). Both go on to the death
 * pages. Viewers see it too — nothing on it is private.
 *
 * <p>Opaque: vanilla removes the dead player at death tick 20, which takes the train with it, so the
 * world must not show through. A viewer taking the stream over while it is open (the relay cuts this
 * one off) moves straight on.</p>
 */
public final class LivePostMortemChoiceScreen extends Screen {

    private static final int BUTTON_W = 200;
    private static final int BUTTON_H = 20;
    private static final int GAP = 6;
    private static final int TOP = 0xFF140404;
    private static final int BOTTOM = 0xFF3A0C0C;

    private final Supplier<Screen> next;
    private MultiLineLabel body = MultiLineLabel.EMPTY;
    private boolean done;

    public LivePostMortemChoiceScreen(Supplier<Screen> next) {
        super(Component.translatable("gui.dungeontrain.live.post_mortem.title"));
        this.next = next;
    }

    @Override
    protected void init() {
        body = MultiLineLabel.create(font, Component.translatable("gui.dungeontrain.live.post_mortem.body"), Math.min(320, width - 40));
        int x = (width - BUTTON_W) / 2;
        int y = height / 2 + 10;
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.live.post_mortem.continue"), b -> choose(true))
            .bounds(x, y, BUTTON_W, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.live.post_mortem.end"), b -> choose(false))
            .bounds(x, y + BUTTON_H + GAP, BUTTON_W, BUTTON_H).build());
    }

    private void choose(boolean carryOn) {
        LiveStreamController live = LiveStreamController.get();
        if (carryOn) {
            live.continuePostMortem();
        } else {
            live.endPostMortem();
        }
        moveOn();
    }

    private void moveOn() {
        if (done || minecraft == null) return;
        done = true;
        minecraft.setScreen(next.get());
    }

    @Override
    public void tick() {
        // Taken over by a viewer, or ended some other way: nothing left to choose.
        if (!LiveStreamController.get().postMortemChoicePending()) moveOn();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, TOP, BOTTOM);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int titleY = height / 2 - 30 - body.getLineCount() * font.lineHeight;
        g.drawCenteredString(font, title, width / 2, titleY, 0xFFFF5555);
        body.renderCentered(g, width / 2, titleY + font.lineHeight + 8, font.lineHeight, 0xFFDDDDDD);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
