package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * Darkens the translation screens further when they are opened over a world.
 *
 * <p>Vanilla's in-world screen background is a blurred, ~75%-dark tint of the game behind it,
 * which is fine for a pause menu but not for two panes of small text: a bright, moving train
 * bleeds through and the rows become hard to read. At the title screen the backdrop is the
 * dirt/panorama and vanilla's own rendering is left alone.</p>
 */
final class InWorldBackdrop {

    /** Half-opaque black laid over vanilla's tint — 50% more coverage of what shows through. */
    private static final int EXTRA_TINT = 0x80000000;

    private InWorldBackdrop() {}

    /** Call in place of {@code super.renderBackground(...)}. */
    static void render(Screen screen, GuiGraphics g, Runnable vanilla) {
        vanilla.run();
        if (Minecraft.getInstance().level != null) {
            g.fill(0, 0, screen.width, screen.height, EXTRA_TINT);
        }
    }
}
