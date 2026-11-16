package games.brennan.dungeontrain.client.menu;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * "Step off the train?" — the step between the pause menu's red Abandon button and the kill it sends.
 *
 * <p>The button says <i>abandon</i>, which reads like leaving; what actually happens is that the
 * character dies and the run is over. One misclick used to be enough. So the screen lays the two
 * ways out side by side, as two cards of equal height with their buttons on one line:</p>
 * <ul>
 *   <li><b>left, gold</b> — the way to stop without dying: a naturally spawning bed saves and
 *       quits; or put things in an Ender Chest to keep them for the next life (that line only when
 *       there is something to keep). Its button, <i>Stay aboard</i>, changes nothing.</li>
 *   <li><b>right, red</b> — stepping off is fatal, and the stacks the player would lose, most
 *       valuable first ({@link AbandonInventoryGrid}). Its button is the death.</li>
 * </ul>
 *
 * <p>Hovering <i>Step off and die</i> washes the screen red, and the carried items with it at
 * half strength. Escape falls back to the pause menu. On a screen too narrow for two cards they stack, the
 * gold one first. What "continue" means is the caller's: {@code proceed} runs only once the
 * player has said yes.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AbandonConfirmScreen extends Screen {

    private static final int TEXT_MARGIN = 16;
    private static final int CARD_GAP = 8;
    private static final int MIN_CARD_W = 150;
    private static final int MAX_CARD_W = 210;
    private static final int MAX_STACKED_W = 320;
    private static final int GRID_COLS = 8;
    private static final int TITLE_GAP = 6;
    private static final int EDGE = 4;

    private static final int WHITE = 0xFFFFFF;
    private static final int RED = 0xFF6060;
    private static final int GREY = 0xA0A0A0;
    private static final int LIGHT = 0xE0E0E0;
    private static final int GOLD = 0xFFD860;
    private static final int GOLD_BORDER = 0xC0FFD860;
    private static final int RED_BORDER = 0xC0FF6060;

    /** Red wash over the world at full hover, and how long it takes to fade in or out. */
    private static final int WASH_RGB = 0xB00000;
    private static final int WASH_MAX_ALPHA = 0x90;
    private static final float WASH_FADE_MS = 400F;
    /** How strongly the carried items react at full hover: half. */
    private static final float ITEM_FADE = 0.5F;

    private static final Component SAVE_TITLE = Component.translatable("gui.dungeontrain.abandon_confirm.save_title");
    private static final Component SAVE_BODY = Component.translatable("gui.dungeontrain.abandon_confirm.save_body");
    private static final Component ENDER_CHEST = Component.translatable("gui.dungeontrain.abandon_confirm.ender_chest");
    private static final Component FATAL = Component.translatable("gui.dungeontrain.abandon_confirm.fatal");

    private final Screen lastScreen;
    private final Runnable proceed;
    private AbandonTextBox titleBox;
    private AbandonCard keep;
    private AbandonCard fatal;
    private Button stepOff;
    private float wash;
    private long lastWashMs = -1;

    /**
     * @param lastScreen the pause menu to fall back to — cancelling must leave the run exactly as it was
     * @param proceed    the abandon itself, run once the player has confirmed the death
     */
    public AbandonConfirmScreen(Screen lastScreen, Runnable proceed) {
        super(Component.translatable("gui.dungeontrain.abandon_confirm.title"));
        this.lastScreen = lastScreen;
        this.proceed = proceed;
    }

    @Override
    protected void init() {
        int room = this.width - 2 * TEXT_MARGIN;
        boolean stacked = (room - CARD_GAP) / 2 < MIN_CARD_W;
        int cardW = stacked ? Math.min(MAX_STACKED_W, room) : Math.min(MAX_CARD_W, (room - CARD_GAP) / 2);

        AbandonInventoryGrid grid = AbandonInventoryGrid.of(Minecraft.getInstance().player);
        titleBox = AbandonTextBox.of(this.font, room, ItemStack.EMPTY, this.title, WHITE);
        keep = new AbandonCard(this.font, cardW, GOLD_BORDER)
                .text(new ItemStack(Items.RED_BED), SAVE_TITLE, GOLD)
                .text(ItemStack.EMPTY, SAVE_BODY, LIGHT);
        if (grid != null) keep.text(new ItemStack(Items.ENDER_CHEST), ENDER_CHEST, GREY);
        fatal = new AbandonCard(this.font, cardW, RED_BORDER).text(ItemStack.EMPTY, FATAL, RED);

        int above = titleBox.height() + TITLE_GAP + (stacked ? keep.naturalHeight() + CARD_GAP : 0);
        int rowsRoom = this.height - 2 * EDGE - above - fatal.baseHeight() - AbandonCard.GAP;
        int cols = Math.min(GRID_COLS, fatal.innerWidth() / AbandonInventoryGrid.SLOT);
        if (grid != null && grid.layout(cols, rowsRoom / AbandonInventoryGrid.SLOT)) fatal.grid(grid);

        layoutCards(stacked, cardW);
    }

    private void layoutCards(boolean stacked, int cardW) {
        int keepH = keep.naturalHeight();
        int fatalH = fatal.naturalHeight();
        if (!stacked) keepH = fatalH = Math.max(keepH, fatalH);
        int cardsH = stacked ? keepH + CARD_GAP + fatalH : keepH;
        int y = Math.max(EDGE, (this.height - titleBox.height() - TITLE_GAP - cardsH) / 2);

        titleBox.place((this.width - titleBox.width()) / 2, y);
        y += titleBox.height() + TITLE_GAP;
        if (stacked) {
            int x = (this.width - cardW) / 2;
            keep.place(x, y, keepH);
            fatal.place(x, y + keepH + CARD_GAP, fatalH);
        } else {
            int x = (this.width - (2 * cardW + CARD_GAP)) / 2;
            keep.place(x, y, keepH);
            fatal.place(x + cardW + CARD_GAP, y, fatalH);
        }
        addButtons(cardW);
    }

    private void addButtons(int cardW) {
        int w = cardW - 2 * AbandonCard.PAD;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.abandon_confirm.back"),
                        b -> this.onClose())
                .bounds(keep.x() + AbandonCard.PAD, keep.buttonY(), w, AbandonCard.BUTTON_H).build());

        // The same red the Abandon button carries on the pause menu — the colour is the warning.
        stepOff = this.addRenderableWidget(new ColorTintedButton(
                fatal.x() + AbandonCard.PAD, fatal.buttonY(), w, AbandonCard.BUTTON_H,
                Component.translatable("gui.dungeontrain.abandon_confirm.yes"),
                1.0F, 0.30F, 0.30F,
                b -> proceedNow()));
    }

    /** The world behind the screen, washed red while the player hovers "Step off and die". */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        updateWash();
        int alpha = Math.round(wash * WASH_MAX_ALPHA);
        if (alpha > 0) g.fill(0, 0, this.width, this.height, (alpha << 24) | WASH_RGB);
        // Cards go under the buttons, which super.render draws next.
        titleBox.render(g, this.font);
        keep.render(g, 0F);
        fatal.render(g, wash * ITEM_FADE);
    }

    /** Eases {@link #wash} toward 1 while the step-off button is hovered, back to 0 otherwise. */
    private void updateWash() {
        long now = Util.getMillis();
        float step = lastWashMs < 0 ? 0F : (now - lastWashMs) / WASH_FADE_MS;
        lastWashMs = now;
        boolean hot = stepOff != null && stepOff.isHovered();
        wash = hot ? Math.min(1F, wash + step) : Math.max(0F, wash - step);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        fatal.renderTooltip(g, mouseX, mouseY);
    }

    /** Escape and Stay aboard land in the same place: the pause menu, run untouched. */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(lastScreen);
    }

    private void proceedNow() {
        Minecraft.getInstance().setScreen(null);
        proceed.run();
    }
}
