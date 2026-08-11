package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.Consumer;

/**
 * The New screen's first row: which of the four builder modes you're making something for, shown
 * as the same picture the Train Builder picker used.
 *
 * <p>A {@code CycleButton} would have been fewer lines, but it draws the vanilla button sprite and
 * has nowhere to put a screenshot behind its label. Since the picker you arrived from was a wall of
 * images, carrying the image through is what makes the two screens read as the same choice — so
 * this cycles like a cycle button and renders like a tile.</p>
 *
 * <p>Inactive (the Save-as screen locks it) it still shows its picture, dimmed and without the
 * hover border: it is describing what the build already is, not offering to change it.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderModeArtButton extends Button {

    private static final int BORDER_HOVER = 0xFFFFFFFF;
    private static final int BORDER_IDLE = 0xFF000000;
    private static final int LABEL_STRIP_BG = 0xC0101010;
    private static final int LABEL_COLOUR = 0xFFFFFF;
    private static final int IDLE_DIM = 0x40000000;
    private static final int INACTIVE_DIM = 0x80000000;

    private static final int LABEL_STRIP_H = 14;

    private BuilderMode mode;
    private final boolean[] textureAvailable = new boolean[BuilderMode.values().length];
    private final Consumer<BuilderMode> onChange;

    BuilderModeArtButton(int x, int y, int width, int height, BuilderMode initial,
                         Consumer<BuilderMode> onChange) {
        super(x, y, width, height, Component.translatable(initial.labelKey()),
                b -> {}, DEFAULT_NARRATION);
        this.mode = initial;
        this.onChange = onChange;
        // Probed once per mode rather than per frame: a screen rebuilds its widgets on resource
        // reload anyway, so art that appears later is still picked up.
        for (BuilderMode m : BuilderMode.values()) {
            textureAvailable[m.ordinal()] = BuilderTileArt.isAvailable(m);
        }
    }

    @Override
    public void onPress() {
        BuilderMode[] all = BuilderMode.values();
        mode = all[(mode.ordinal() + 1) % all.length];
        setMessage(Component.translatable(mode.labelKey()));
        onChange.accept(mode);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        boolean hovered = this.active && this.isHoveredOrFocused();

        BuilderTileArt.render(g, mode, textureAvailable[mode.ordinal()], x, y, w, h, this.alpha);
        g.fill(x, y, x + w, y + h, this.active ? (hovered ? 0 : IDLE_DIM) : INACTIVE_DIM);
        g.renderOutline(x, y, w, h, hovered ? BORDER_HOVER : BORDER_IDLE);

        int stripTop = y + h - LABEL_STRIP_H;
        g.fill(x + 1, stripTop, x + w - 1, y + h - 1, LABEL_STRIP_BG);
        Minecraft mc = Minecraft.getInstance();
        g.drawCenteredString(mc.font, this.getMessage(), x + w / 2,
                stripTop + (LABEL_STRIP_H - mc.font.lineHeight) / 2, LABEL_COLOUR);
    }
}
