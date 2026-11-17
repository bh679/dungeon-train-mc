package games.brennan.dungeontrain.client.support;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/**
 * The "on behalf of" picker: a small face button beside the currency button ([face] ▾) and the list
 * it opens: "You (name)" and "Someone else…", the latter with a name box under it. The choice lives
 * in {@link DonationRecipient}; this only shows and edits it.
 *
 * <p>Driven by the host exactly like {@code currency/CurrencyPicker}: the button is an ordinary
 * widget, the list is drawn last at tooltip height, and the host gives it first refusal on clicks,
 * keys and typed characters. The name box is owned here rather than added to the host, so it can
 * be drawn inside the list and survives the host re-laying itself out.</p>
 */
public final class RecipientPicker {

    private static final int BUTTON_H = 14;
    private static final int FACE = 8;
    private static final int ROW_FACE = 8;
    private static final int ROW_H = 12;
    private static final int BOX_H = 14;
    private static final int PAD = 3;
    private static final float Z = 400.0F;
    /** Typing has to pause this long before a name is looked up, so no request per keystroke. */
    private static final long LOOKUP_DELAY_MS = 400;

    private static final int FACE_BG = 0xFF4A4A4A;
    private static final int FACE_HOVER = 0xFF5E5E5E;
    private static final int EDGE = 0xFF000000;
    private static final int LIGHT = 0xFF777777;
    private static final int SHADE = 0xFF2A2A2A;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int LIST_BG = 0xF0101010;
    private static final int LIST_BORDER = 0xFF5A5A5A;
    private static final int ROW_HOVER = 0x40FFFFFF;
    private static final int ROW_SELECTED = 0xFF3A3A3A;
    static final int COLOUR_GIFTING = 0xFF8FE08A;
    static final int COLOUR_BLOCKED = 0xFFFF6B6B;

    /** The picker last laid out, so a blocked money click can open it (see {@link #openActive()}). */
    private static WeakReference<RecipientPicker> active = new WeakReference<>(null);

    private final Runnable relayout;
    @Nullable private FaceButton button;
    @Nullable private EditBox nameBox;
    private boolean open;
    private int listX, listY, listW;
    private long lastTypedMs;

    /** {@code relayout} is the host's {@code rebuildWidgets}, run when the choice changes. */
    public RecipientPicker(Runnable relayout) {
        this.relayout = relayout;
    }

    /**
     * Place the button with its right edge at {@code rightX} and its top at {@code y}, adding it
     * through {@code add}; the list opens under it, right-aligned.
     */
    public void layout(Font font, int rightX, int y, Consumer<AbstractWidget> add) {
        int w = PAD + FACE + PAD + font.width("▾") + PAD;
        button = new FaceButton(rightX - w, y, w, font);
        button.setTooltip(Tooltip.create(tooltip()));
        add.accept(button);

        listW = Math.max(110, PAD + ROW_FACE + PAD
                + Math.max(font.width(selfLabel()), font.width(otherLabel())) + PAD * 2);
        listX = rightX - listW;
        listY = y + BUTTON_H + 1;
        if (nameBox == null) {
            nameBox = new EditBox(font, 0, 0, 0, BOX_H, Component.translatable("gui.dungeontrain.contribute.recipient.name_hint"));
            nameBox.setMaxLength(DonationRecipient.MAX_NAME);
            nameBox.setFilter(s -> s.matches("[A-Za-z0-9_]*"));
            nameBox.setValue(DonationRecipient.typed());
            nameBox.setHint(Component.translatable("gui.dungeontrain.contribute.recipient.name_hint"));
            nameBox.setResponder(s -> {
                DonationRecipient.setTyped(s);
                lastTypedMs = System.currentTimeMillis();
            });
        }
        // The choice can change outside the box (a reset, the dev preview): show what will be sent.
        if (!nameBox.getValue().equals(DonationRecipient.typed())) nameBox.setValue(DonationRecipient.typed());
        nameBox.setX(listX + PAD);
        nameBox.setY(listY + 1 + 2 * ROW_H + 2);
        nameBox.setWidth(listW - PAD * 2);
        active = new WeakReference<>(this);
    }

    /** The button, for hosts that draw it themselves (the Community Rewards page). */
    @Nullable
    public AbstractWidget button() {
        return button;
    }

    /** Open the last laid-out picker on its name box: a money click while no valid name is typed. */
    static void openActive() {
        RecipientPicker p = active.get();
        if (p == null || p.button == null) return;
        p.open = true;
        if (p.nameBox != null) p.nameBox.setFocused(true);
    }

    private int listH() {
        return 2 + 2 * ROW_H + (DonationRecipient.isOther() ? BOX_H + 4 : 0);
    }

    private boolean overList(double mx, double my) {
        return open && mx >= listX && mx < listX + listW && my >= listY && my < listY + listH();
    }

    /** Draw the open list over everything; call last in the host's render. */
    public void render(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTick) {
        if (button != null) button.setTooltip(Tooltip.create(tooltip()));
        if (!open || button == null) return;
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, Z);
        try {
            int h = listH();
            g.fill(listX, listY, listX + listW, listY + h, LIST_BG);
            g.renderOutline(listX, listY, listW, h, LIST_BORDER);
            row(g, font, 0, RecipientFaces.self(), selfLabel(), !DonationRecipient.isOther(), mouseX, mouseY);
            row(g, font, 1, otherSkin(), otherLabel(), DonationRecipient.isOther(), mouseX, mouseY);
            if (DonationRecipient.isOther() && nameBox != null) nameBox.render(g, mouseX, mouseY, partialTick);
        } finally {
            g.pose().popPose();
        }
    }

    private void row(GuiGraphics g, Font font, int i, PlayerSkin skin, Component label, boolean selected,
                     int mouseX, int mouseY) {
        int rowY = listY + 1 + i * ROW_H;
        if (selected) g.fill(listX + 1, rowY, listX + listW - 1, rowY + ROW_H, ROW_SELECTED);
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_H) {
            g.fill(listX + 1, rowY, listX + listW - 1, rowY + ROW_H, ROW_HOVER);
        }
        PlayerFaceRenderer.draw(g, skin, listX + PAD, rowY + (ROW_H - ROW_FACE) / 2, ROW_FACE);
        g.drawString(font, label, listX + PAD + ROW_FACE + PAD, rowY + 2, TEXT, false);
    }

    /** First refusal on a click: picks a row or the name box, or closes the list on a click elsewhere. */
    public boolean mouseClicked(double mx, double my, int mouseButton) {
        if (!open) return false;
        if (overList(mx, my)) {
            int idx = (int) ((my - listY - 1) / ROW_H);
            if (mouseButton == 0 && idx == 0) {
                open = false;
                DonationRecipient.chooseSelf();
                relayout.run();
            } else if (mouseButton == 0 && idx == 1 && !DonationRecipient.isOther()) {
                DonationRecipient.chooseOther();
                if (nameBox != null) nameBox.setFocused(true);
                relayout.run();
            } else if (nameBox != null && DonationRecipient.isOther()) {
                nameBox.setFocused(nameBox.mouseClicked(mx, my, mouseButton) || nameBox.isMouseOver(mx, my));
            }
            return true;
        }
        close();
        // A click on the button itself closes the list and is consumed here, so it doesn't reopen it.
        return button != null && button.isMouseOver(mx, my);
    }

    /** Esc or Enter closes an open list instead of the screen; other keys go to the name box. */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            close();
            return true;
        }
        return typing() && nameBox.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char c, int modifiers) {
        return open && typing() && nameBox.charTyped(c, modifiers);
    }

    private boolean typing() {
        return DonationRecipient.isOther() && nameBox != null && nameBox.isFocused();
    }

    private void close() {
        open = false;
        if (nameBox != null) nameBox.setFocused(false);
    }

    private PlayerSkin otherSkin() {
        boolean settled = System.currentTimeMillis() - lastTypedMs >= LOOKUP_DELAY_MS;
        return RecipientFaces.of(DonationRecipient.typed(), settled);
    }

    private static Component selfLabel() {
        return Component.translatable("gui.dungeontrain.contribute.recipient.self", selfName());
    }

    private static Component otherLabel() {
        return Component.translatable("gui.dungeontrain.contribute.recipient.other");
    }

    private static Component tooltip() {
        if (DonationRecipient.isGifting()) {
            return Component.translatable("gui.dungeontrain.contribute.recipient.gifting", DonationRecipient.typed());
        }
        if (DonationRecipient.blocked()) return Component.translatable("gui.dungeontrain.contribute.recipient.enter_name");
        return Component.translatable("gui.dungeontrain.contribute.recipient.as_you");
    }

    static String selfName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() != null ? mc.getUser().getName() : "Player";
    }

    /** The widest the status line can be for the current name, for a host deciding where it fits. */
    public static int statusWidth(Font font) {
        String name = DonationRecipient.typed().isEmpty() ? "MMMMMMMMMMMMMMMM" : DonationRecipient.typed();
        return Math.max(font.width(Component.translatable("gui.dungeontrain.contribute.recipient.gifting", name)),
                font.width(Component.translatable("gui.dungeontrain.contribute.recipient.enter_name")));
    }

    /**
     * The one status line under the header while "Someone else" is chosen: green "Gifting for Steve",
     * or red "Enter a Minecraft name" until a valid one is typed. Draws nothing for the player themself.
     */
    public static void drawStatus(GuiGraphics g, Font font, int rightX, int y) {
        if (!DonationRecipient.isOther()) return;
        Component line = DonationRecipient.isGifting()
                ? Component.translatable("gui.dungeontrain.contribute.recipient.gifting", DonationRecipient.typed())
                : Component.translatable("gui.dungeontrain.contribute.recipient.enter_name");
        int colour = DonationRecipient.isGifting() ? COLOUR_GIFTING : COLOUR_BLOCKED;
        g.drawString(font, line, rightX - font.width(line), y, colour, false);
    }

    /** The [face] ▾ button, bevelled like the currency chip; edged green while gifting, red while blocked. */
    private final class FaceButton extends AbstractButton {
        private final Font font;

        FaceButton(int x, int y, int w, Font font) {
            super(x, y, w, BUTTON_H, Component.translatable("gui.dungeontrain.contribute.recipient.button"));
            this.font = font;
        }

        @Override
        public void onPress() {
            open = !open;
            if (!open) close();
            else if (DonationRecipient.blocked() && nameBox != null) nameBox.setFocused(true);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int edge = DonationRecipient.isGifting() ? COLOUR_GIFTING
                    : DonationRecipient.blocked() ? COLOUR_BLOCKED : EDGE;
            g.fill(x, y, x + w, y + h, edge);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, isHoveredOrFocused() || open ? FACE_HOVER : FACE_BG);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, LIGHT);
            g.fill(x + 1, y + 1, x + 2, y + h - 1, LIGHT);
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, SHADE);
            g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, SHADE);
            PlayerSkin skin = DonationRecipient.isOther() ? otherSkin() : RecipientFaces.self();
            PlayerFaceRenderer.draw(g, skin, x + PAD, y + (h - FACE) / 2, FACE);
            g.drawString(font, "▾", x + PAD + FACE + PAD, y + (h - font.lineHeight) / 2 + 1, TEXT, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }
}
