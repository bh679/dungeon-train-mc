package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

/**
 * The handle between the English and the box the translation is typed into.
 *
 * <p>{@link TranslationSourceLayout#viewportHeight} picks half the window when nobody has said
 * otherwise, and that guess is wrong in both directions: reading a long book variant wants more
 * English than half, and typing a long replacement wants it out of the way. Neither is a call the
 * screen can make, so this hands it to the translator.</p>
 *
 * <p>Lives in the gap that was already there between the two, so making the divider draggable
 * costs no vertical space at all.</p>
 */
public final class TranslationPaneSplitter extends AbstractWidget {

    /** Half the width of the brighter centre grip — the part that says "pull me". */
    private static final int GRIP_HALF_WIDTH = 14;
    private static final int RULE_COLOUR = 0x40FFFFFF;
    private static final int GRIP_COLOUR = 0x80FFFFFF;
    private static final int GRIP_ACTIVE = 0xFFFFFFFF;

    private final DoubleConsumer onDrag;

    private boolean dragging;

    public TranslationPaneSplitter(int x, int y, int width, int height, DoubleConsumer onDrag) {
        super(x, y, width, height,
            Component.translatable("gui.dungeontrain.translate.edit.resize"));
        this.onDrag = onDrag;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int midY = getY() + height / 2;
        g.fill(getX(), midY, getX() + width, midY + 1, RULE_COLOUR);
        // A rule alone reads as a border. The grip is what makes it look like a handle, so it is
        // drawn brighter, and brighter again once the cursor is on it.
        int centre = getX() + width / 2;
        int colour = dragging || isHovered() ? GRIP_ACTIVE : GRIP_COLOUR;
        g.fill(centre - GRIP_HALF_WIDTH, midY - 1, centre + GRIP_HALF_WIDTH, midY + 2, colour);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        dragging = true;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
                                double dragY) {
        if (!dragging) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        // Reported raw. Where the divider is allowed to end up is the screen's arithmetic, not
        // this widget's — it does not know what else is on the screen.
        onDrag.accept(mouseY);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Nothing here to type into, so Tab passes straight over it to the edit box. */
    @Override
    public ComponentPath nextFocusPath(FocusNavigationEvent event) {
        return null;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
