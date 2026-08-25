package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * One of the two answers on {@link CustomContentPromptScreen}, drawn as a card rather than a
 * button: an icon, the thing you get, and a one-word tag for what it costs.
 *
 * <p>The question has exactly two answers and they are not symmetrical — one plays your own
 * designs and gives up your stats, the other plays the shipped game and keeps them. Stacked
 * buttons made the player read two paragraphs to find that out. Side by side, with the Free Play
 * badge itself as one of the icons, the trade is the thing you look at.</p>
 */
public final class ContentChoiceCard extends AbstractWidget {

    private static final int BORDER = 0xFF3A3A4A;
    private static final int BORDER_HOVER = 0xFF8A8AA8;
    private static final int FILL = 0x40000000;
    private static final int FILL_HOVER = 0x30FFFFFF;
    private static final int COLOUR_NAME = 0xFFFFFFFF;

    private static final int ICON_SIZE = 32;
    private static final int PAD = 9;
    private static final int NAME_GAP = 7;
    private static final int TAG_GAP = 3;

    /** ENTER, SPACE, NUMPAD_ENTER — vanilla's own activation keys for a focused widget. */
    private static final int KEY_ENTER = 257;
    private static final int KEY_SPACE = 32;
    private static final int KEY_NUMPAD_ENTER = 335;

    /** GUI sprite id, e.g. {@code dungeontrain:icon/free_play}. */
    private final ResourceLocation icon;
    private final Component name;
    private final Component tag;
    private final int tagColour;
    private final Runnable onPick;

    public ContentChoiceCard(int x, int y, int w, int h,
                             ResourceLocation icon,
                             Component name, Component tag, int tagColour,
                             Runnable onPick) {
        super(x, y, w, h, name);
        this.icon = icon;
        this.name = name;
        this.tag = tag;
        this.tagColour = tagColour;
        this.onPick = onPick;
    }

    /** Height that fits the icon, the name and the tag at the current font size. */
    public static int heightFor(int lineHeight) {
        return PAD + ICON_SIZE + NAME_GAP + lineHeight + TAG_GAP + lineHeight + PAD;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean lit = isHoveredOrFocused();
        g.fill(getX(), getY(), getX() + width, getY() + height, lit ? FILL_HOVER : FILL);
        g.renderOutline(getX(), getY(), width, height, lit ? BORDER_HOVER : BORDER);

        int cx = getX() + width / 2;
        // blitSprite, not blit: a standalone-texture blit lands in a different render batch from
        // the panel's flat fills and ends up *under* the near-opaque frame background. The GUI
        // sprite path is what the rest of this screen (and BookVoteClientEvents) already uses.
        g.blitSprite(icon, cx - ICON_SIZE / 2, getY() + PAD, ICON_SIZE, ICON_SIZE);

        var font = Minecraft.getInstance().font;
        int y = getY() + PAD + ICON_SIZE + NAME_GAP;
        g.drawCenteredString(font, name, cx, y, COLOUR_NAME);
        g.drawCenteredString(font, tag, cx, y + font.lineHeight + TAG_GAP, tagColour);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        onPick.run();
    }

    /** Keyboard / controller activation, so the cards are reachable without a mouse. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.active || !this.visible) return false;
        if (keyCode == KEY_ENTER || keyCode == KEY_SPACE || keyCode == KEY_NUMPAD_ENTER) {
            onPick.run();
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // Both halves: "Custom Train" alone omits the very thing being traded.
        output.add(NarratedElementType.TITLE,
            Component.empty().append(name).append(CommonComponents.SPACE).append(tag));
        if (isFocused()) {
            output.add(NarratedElementType.USAGE,
                Component.translatable("narration.button.usage.focused"));
        }
    }
}
