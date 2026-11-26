package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.advancement.TotemPoleChecklist;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Totem Pole's checklist: a frame for every Dungeon Totem, the ones that have saved the player showing the
 * totem and its name in the order they were found ({@link TotemPoleChecklist#slots}), the rest an empty frame
 * marked ???. Laid out and drawn like a photo album ({@link EarnedPhotoAlbumScreen}), over the advancements
 * screen. Opened by clicking the advancement ({@link AdvancementTrackClick}), earned or not; scroll with the
 * wheel, and a click or Esc puts it away.
 */
public final class TotemPoleScreen extends Screen {

    private static final float OVERLAY_Z = 1000F;
    private static final int CELL = 72;
    /** Room under a frame for its name: two lines, since a totem's name runs wider than the frame. */
    private static final int LABEL = 22;
    private static final int GAP = 10;
    private static final int TOP = 44;
    private static final int SCROLL_STEP = 24;
    private static final float ICON_SCALE = 3F;
    private static final int FRAME_COLOUR = 0xFF6E6A62;
    private static final int USED_FILL = 0xFF3A3732;
    private static final int MISSING_FILL = 0xFF2A2825;
    private static final int QUESTION_COLOUR = 0xFF8C877D;

    private final Screen parent;
    private final List<TotemPoleChecklist.Slot> slots;
    private int scroll;

    public TotemPoleScreen(Screen parent, Component title, List<TotemPoleChecklist.Slot> slots) {
        super(title);
        this.parent = parent;
        this.slots = slots;
    }

    @Override
    protected void init() {
        if (parent != null && (parent.width != width || parent.height != height)) {
            parent.resize(minecraft, width, height);
        }
        scroll = Math.min(scroll, maxScroll());
    }

    private int columns() {
        return Math.max(1, Math.min(slots.size(), (width - 2 * GAP) / (CELL + GAP)));
    }

    private int gridLeft() {
        int cols = columns();
        return (width - (cols * CELL + (cols - 1) * GAP)) / 2;
    }

    private int maxScroll() {
        int rows = (slots.size() + columns() - 1) / columns();
        return Math.max(0, rows * (CELL + LABEL + GAP) - (height - TOP - GAP));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (parent != null) {
            parent.render(g, -1, -1, partialTick);
        } else {
            super.renderBackground(g, mouseX, mouseY, partialTick);
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        g.fill(0, 0, width, height, 0xE8000000);
        g.pose().popPose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        g.fill(0, 0, width, TOP - 4, 0xFF000000);   // the advancements screen's own title would show through ours
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        long used = slots.stream().filter(this::shows).count();
        g.drawCenteredString(font, Component.translatable("gui.dungeontrain.totem_pole.count", used + "/" + slots.size()),
                width / 2, 24, 0xFFAAAAAA);
        g.enableScissor(0, TOP - 2, width, height);
        int cols = columns();
        int left = gridLeft();
        for (int i = 0; i < slots.size(); i++) {
            int x = left + (i % cols) * (CELL + GAP);
            int y = TOP + (i / cols) * (CELL + LABEL + GAP) - scroll;
            if (y + CELL + LABEL < TOP || y > height) continue;
            drawSlot(g, slots.get(i), x, y);
        }
        g.disableScissor();
        g.pose().popPose();
    }

    /** A used totem the game knows; anything else draws as a ??? frame. */
    private boolean shows(TotemPoleChecklist.Slot slot) {
        return slot.used() && item(slot) != Items.AIR;
    }

    private static Item item(TotemPoleChecklist.Slot slot) {
        return BuiltInRegistries.ITEM.get(slot.item());
    }

    private void drawSlot(GuiGraphics g, TotemPoleChecklist.Slot slot, int x, int y) {
        boolean shown = shows(slot);
        g.fill(x, y, x + CELL, y + CELL, FRAME_COLOUR);
        g.fill(x + 3, y + 3, x + CELL - 3, y + CELL - 3, shown ? USED_FILL : MISSING_FILL);
        Component label;
        if (shown) {
            ItemStack stack = new ItemStack(item(slot));
            g.pose().pushPose();
            g.pose().translate(x + CELL / 2F - 8 * ICON_SCALE, y + CELL / 2F - 8 * ICON_SCALE, 0);
            g.pose().scale(ICON_SCALE, ICON_SCALE, 1F);
            g.renderItem(stack, 0, 0);
            g.pose().popPose();
            label = stack.getHoverName();
        } else {
            drawQuestionMark(g, x, y);
            label = Component.translatable("advancements.dungeontrain.hidden_description");
        }
        List<FormattedCharSequence> lines = font.split(label, CELL + GAP);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            FormattedCharSequence line = lines.get(i);
            g.drawString(font, line, x + (CELL - font.width(line)) / 2, y + CELL + 2 + i * 10, 0xFFFFFFFF, true);
        }
    }

    private void drawQuestionMark(GuiGraphics g, int x, int y) {
        float scale = 4F;
        g.pose().pushPose();
        g.pose().translate(x + CELL / 2F - font.width("?") * scale / 2F, y + CELL / 2F - 4 * scale, 0);
        g.pose().scale(scale, scale, 1F);
        g.drawString(font, "?", 0, 0, QUESTION_COLOUR, false);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        onClose();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(scrollY * SCROLL_STEP)));
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** Pauses when the screen it opened over does (the advancements screen), so the train doesn't run on behind it. */
    @Override
    public boolean isPauseScreen() {
        return parent != null && parent.isPauseScreen();
    }
}
