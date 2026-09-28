package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.client.menu.editorscreen.EditorIcons;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The square trash button at the end of My Builds' action row.
 *
 * <p>An icon rather than a word, and a square rather than a third half-width button: the two
 * actions beside it are things you do to a build and keep it, and this one is not. Set apart by
 * shape it reads as the exception it is, and a row of three equal "Submit / Load / Delete" buttons
 * would put the irreversible one a slip of the mouse from the two ordinary ones. The same
 * {@link EditorIcons#TRASH} sprite the editor's own delete uses, so it is one glyph across the mod.</p>
 *
 * <p>The tooltip carries the words the icon does not, and is the accessible name too.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTrashButton extends Button {

    static final int SIZE = 20;
    private static final int ICON = 16;
    private static final int DISABLED_ICON_TINT = 0x80FFFFFF;

    BuilderTrashButton(int x, int y, OnPress onPress) {
        super(x, y, SIZE, SIZE,
                Component.translatable("gui.dungeontrain.builder.profile.delete.tooltip"),
                onPress, DEFAULT_NARRATION);
        setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.builder.profile.delete.tooltip")));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // The vanilla button face, without its label: drawn by the parent with an empty message
        // would still centre a zero-width string, so the sprite is laid over the face instead.
        Component message = getMessage();
        setMessage(Component.empty());
        super.renderWidget(g, mouseX, mouseY, partialTick);
        setMessage(message);
        int ix = getX() + (SIZE - ICON) / 2;
        int iy = getY() + (SIZE - ICON) / 2;
        if (this.active) {
            g.blitSprite(EditorIcons.TRASH, ix, iy, ICON, ICON);
        } else {
            // Faded like the face under it, so a greyed button does not carry a bright icon.
            g.setColor(0.5F, 0.5F, 0.5F, 1.0F);
            g.blitSprite(EditorIcons.TRASH, ix, iy, ICON, ICON);
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
