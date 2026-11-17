package games.brennan.dungeontrain.client.nap;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.NapResponsePacket;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * "You feel comfortable here." The nap prompt a natural bed offers ({@code nap/NapBedEvents}), in its
 * daytime or night copy. Main Menu asks once more (the feature is still being tested with players),
 * then saves and quits; Stay awake / Wake up. (or Escape) carries on. That warning lays out everything
 * the player carries (inventory, armour, offhand) under its title, then asks whether they would lose it;
 * hovering its Back suggests an Ender Chest, the way to carry things into the next life.
 *
 * <p>The world fades out behind it over its first {@value #FADE_MS} ms, to 95% black, or fully black
 * when the player is already asleep. The final Main Menu hides the buttons and fades the rest of the
 * way to black over {@value #EXIT_FADE_MS} ms before the answer goes to the server.</p>
 */
public final class NapPromptScreen extends Screen {

    private static final int COLOUR_TITLE = 0xFFFFFFFF;
    private static final int COLOUR_BODY = 0xFFA0A0A0;
    private static final int COLOUR_WARNING = 0xFFFFC857;
    private static final int TEXT_WIDTH = 300;
    private static final int BUTTON_W = 150;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int LINE_GAP = 2;
    private static final int SECTION_GAP = 14;
    /** The warning's item grid: one inventory-sized slot per carried stack, at most this many across. */
    private static final int SLOT = 18;
    private static final int GRID_MAX_COLS = 12;
    private static final int COLOUR_SLOT = 0x60000000;
    private static final int COLOUR_SLOT_EDGE = 0x40FFFFFF;
    /** The Back hint's tooltip: an Ender Chest icon, then the line, padded like a vanilla tooltip. */
    private static final ItemStack ENDER_CHEST = new ItemStack(Items.ENDER_CHEST);
    private static final int HINT_PAD = 4, HINT_ICON = 16, HINT_ICON_GAP = 4, HINT_Z = 400;

    private static final long FADE_MS = 1000L;
    private static final float FADE_AWAKE = 0.95F;
    private static final float FADE_ASLEEP = 1.0F;
    private static final long EXIT_FADE_MS = 2000L;
    /** Black held after saying yes while the server saves; the screen gives up if no answer comes. */
    private static final long EXIT_HOLD_MS = 10000L;

    private final String prefix;
    private final long openedAt = Util.getMillis();
    private float fadeTarget = FADE_AWAKE;
    private boolean responded = false;
    private boolean warning = false;
    /** When the final Main Menu was pressed; -1 until then. */
    private long exitStartedAt = -1L;
    /** The overlay's alpha at that moment, the exit fade's start. */
    private float exitFrom = 0.0F;
    private List<FormattedCharSequence> titleLines = List.of();
    private List<FormattedCharSequence> bodyLines = List.of();
    private int titleColour = COLOUR_TITLE;
    private int textTop;
    /** What the player carries, shown under the warning; empty on the first prompt. */
    private List<ItemStack> carried = List.of();
    private int gridCols;
    private int gridTop;
    /** The warning's Back button, whose hover shows the Ender Chest hint; null on the first prompt. */
    private Button backButton;

    public NapPromptScreen(boolean night) {
        super(Component.translatable(keyPrefix(night) + "title"));
        this.prefix = keyPrefix(night);
    }

    private static String keyPrefix(boolean night) {
        return "gui.dungeontrain.nap." + (night ? "night." : "day.");
    }

    @Override
    protected void init() {
        if (this.minecraft != null && this.minecraft.player != null && this.minecraft.player.isSleeping()) {
            fadeTarget = FADE_ASLEEP;
        }
        int width = Math.min(TEXT_WIDTH, this.width - 40);
        if (warning) {
            titleLines = this.font.split(Component.translatable("gui.dungeontrain.nap.warning"), width);
            titleColour = COLOUR_WARNING;
            carried = carriedItems();
            // The question names what is laid out above it, so it waits for something to name.
            bodyLines = carried.isEmpty() ? List.of()
                : this.font.split(Component.translatable("gui.dungeontrain.nap.warning_items"), width);
        } else {
            carried = List.of();
            titleLines = this.font.split(Component.translatable(prefix + "title")
                .append("\n").append(Component.translatable(prefix + "question")), width);
            bodyLines = this.font.split(Component.translatable(prefix + "body"), width);
            titleColour = COLOUR_TITLE;
        }
        int lh = this.font.lineHeight + LINE_GAP;
        gridCols = Math.max(1, Math.min(carried.size(), Math.min(GRID_MAX_COLS, width / SLOT)));
        int gridRows = carried.isEmpty() ? 0 : (carried.size() + gridCols - 1) / gridCols;
        int gridH = gridRows * SLOT;
        int blockH = titleLines.size() * lh + SECTION_GAP
            + (bodyLines.isEmpty() ? 0 : bodyLines.size() * lh + SECTION_GAP)
            + (gridRows == 0 ? 0 : gridH + SECTION_GAP) + BUTTON_H;
        textTop = (this.height - blockH) / 2;
        gridTop = textTop + titleLines.size() * lh + SECTION_GAP;
        int buttonY = textTop + blockH - BUTTON_H;
        int left = this.width / 2 - BUTTON_W - BUTTON_GAP / 2;
        Button mainMenu = warning
            ? Button.builder(Component.translatable("gui.dungeontrain.nap.main_menu"), b -> startExitFade()).build()
            : Button.builder(Component.translatable("gui.dungeontrain.nap.main_menu"), b -> showWarning(true)).build();
        Button other = warning
            ? Button.builder(CommonComponents.GUI_BACK, b -> showWarning(false)).build()
            : Button.builder(Component.translatable(prefix + "decline"), b -> respond(false)).build();
        backButton = warning ? other : null;
        mainMenu.setRectangle(BUTTON_W, BUTTON_H, left, buttonY);
        other.setRectangle(BUTTON_W, BUTTON_H, left + BUTTON_W + BUTTON_GAP, buttonY);
        if (exiting()) return;
        addRenderableWidget(mainMenu);
        addRenderableWidget(other);
    }

    /** Every non-empty stack the player carries: main inventory and hotbar, then armour, then offhand. */
    private List<ItemStack> carriedItems() {
        if (this.minecraft == null || this.minecraft.player == null) return List.of();
        Inventory inv = this.minecraft.player.getInventory();
        List<ItemStack> out = new ArrayList<>();
        for (List<ItemStack> part : List.of(inv.items, inv.armor, inv.offhand)) {
            for (ItemStack stack : part) {
                if (!stack.isEmpty()) out.add(stack.copy());
            }
        }
        return List.copyOf(out);
    }

    private boolean exiting() {
        return exitStartedAt >= 0L;
    }

    /** The final Main Menu: buttons gone, then the fade to full black, then the answer ({@link #tick}). */
    private void startExitFade() {
        if (exiting() || responded) return;
        exitFrom = overlayAlpha();
        exitStartedAt = Util.getMillis();
        clearWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        if (!exiting()) return;
        long elapsed = Util.getMillis() - exitStartedAt;
        if (!responded && elapsed >= EXIT_FADE_MS) {
            // Stays up, black, until the server's NapSavedPacket leaves the world.
            responded = true;
            DungeonTrainNet.sendToServer(new NapResponsePacket(true));
        } else if (responded && elapsed >= EXIT_FADE_MS + EXIT_HOLD_MS && this.minecraft != null) {
            // The server refused (the bed broke, monsters came): back to the game.
            this.minecraft.setScreen(null);
        }
    }

    /** The overlay's current alpha, 0..1. */
    private float overlayAlpha() {
        if (exiting()) {
            float t = Mth.clamp((Util.getMillis() - exitStartedAt) / (float) EXIT_FADE_MS, 0.0F, 1.0F);
            return Mth.lerp(t, exitFrom, 1.0F);
        }
        float t = Mth.clamp((Util.getMillis() - openedAt) / (float) FADE_MS, 0.0F, 1.0F);
        return fadeTarget * t;
    }

    /** Main Menu first shows the in-development warning; its Back returns to the prompt. */
    private void showWarning(boolean on) {
        if (exiting()) return;
        warning = on;
        rebuildWidgets();
    }

    /** The world fades to black behind the prompt instead of vanilla's blur. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int alpha = Math.round(overlayAlpha() * 255.0F);
        g.fill(0, 0, this.width, this.height, alpha << 24);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (exiting()) return;
        int lh = this.font.lineHeight + LINE_GAP;
        int y = textTop;
        for (FormattedCharSequence line : titleLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, titleColour);
            y += lh;
        }
        y += SECTION_GAP;
        if (!carried.isEmpty()) {
            int rows = (carried.size() + gridCols - 1) / gridCols;
            y = gridTop + rows * SLOT + SECTION_GAP;
        }
        // On the warning the body is its question, in the warning's colour, under the items.
        int bodyColour = warning ? COLOUR_WARNING : COLOUR_BODY;
        for (FormattedCharSequence line : bodyLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, bodyColour);
            y += lh;
        }
        renderCarried(g, mouseX, mouseY);
        if (backButton != null && backButton.isHovered()) renderBackHint(g, mouseX, mouseY);
    }

    /** "Find an Ender Chest…" beside the cursor, with the chest's icon: kept on screen. */
    private void renderBackHint(GuiGraphics g, int mouseX, int mouseY) {
        Component text = Component.translatable("gui.dungeontrain.nap.warning_back_hint");
        int w = HINT_ICON + HINT_ICON_GAP + this.font.width(text);
        int h = HINT_ICON;
        int x = Math.min(mouseX + 12, this.width - w - HINT_PAD - 2);
        int y = Math.max(HINT_PAD + 2, mouseY - h - 12);
        g.pose().pushPose();
        g.pose().translate(0, 0, HINT_Z);
        TooltipRenderUtil.renderTooltipBackground(g, x, y, w, h, 0);
        g.renderItem(ENDER_CHEST, x, y);
        g.drawString(this.font, text, x + HINT_ICON + HINT_ICON_GAP, y + (h - this.font.lineHeight) / 2 + 1, 0xFFFFFFFF);
        g.pose().popPose();
    }

    /** The warning's grid of carried items, centred, with the hovered stack's tooltip. */
    private void renderCarried(GuiGraphics g, int mouseX, int mouseY) {
        if (carried.isEmpty()) return;
        int rows = (carried.size() + gridCols - 1) / gridCols;
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < carried.size(); i++) {
            int row = i / gridCols;
            // The last row may be short: centre it on its own.
            int inRow = row == rows - 1 ? carried.size() - row * gridCols : gridCols;
            int x = this.width / 2 - inRow * SLOT / 2 + (i % gridCols) * SLOT;
            int y = gridTop + row * SLOT;
            g.fill(x, y, x + SLOT, y + SLOT, COLOUR_SLOT_EDGE);
            g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, COLOUR_SLOT);
            ItemStack stack = carried.get(i);
            g.renderItem(stack, x + 1, y + 1);
            g.renderItemDecorations(this.font, stack, x + 1, y + 1);
            if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) hovered = stack;
        }
        if (!hovered.isEmpty()) g.renderTooltip(this.font, hovered, mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Escape backs out of the warning first, then says no; it does nothing once the exit fade has begun. */
    @Override
    public void onClose() {
        if (exiting()) return;
        if (warning) {
            showWarning(false);
            return;
        }
        respond(false);
    }

    /**
     * Pushed aside by another screen without an answer, even mid exit fade: that is a no, so the server
     * isn't left waiting and nothing is deleted unless the fade ran to the end.
     */
    @Override
    public void removed() {
        if (!responded) {
            responded = true;
            DungeonTrainNet.sendToServer(new NapResponsePacket(false));
        }
    }

    private void respond(boolean accepted) {
        if (responded) return;
        responded = true;
        DungeonTrainNet.sendToServer(new NapResponsePacket(accepted));
        // Main Menu waits for the server's NapSavedPacket to leave; until then the screen just closes.
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }
}
