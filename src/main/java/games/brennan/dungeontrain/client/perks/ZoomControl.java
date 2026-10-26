package games.brennan.dungeontrain.client.perks;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The zoom steps as clickable cells beside the "Perks" title: whole sky, close and (at small GUI scales)
 * closest. Each
 * cell is just a dot that grows with the zoom, no box; the current step is bright, the others faded.
 */
@OnlyIn(Dist.CLIENT)
final class ZoomControl {

    private static final int CELL = 12, GAP = 2;
    private static final double[][] DOT = {{1.5, 3.0}, {1.5, 2.5, 3.5}};
    private static final String[][] KEYS = {{"out", "in"}, {"out", "in", "closest"}};

    private int x, y, steps = 2;

    /**
     * Lay out {@code steps} cells (2 or 3) with the left edge at {@code left}, centred on the header line
     * {@code midY}. Returns the right edge.
     */
    int layout(int left, int midY, int steps) {
        x = left;
        y = midY - CELL / 2;
        this.steps = Math.max(2, Math.min(3, steps));
        return x + this.steps * CELL + (this.steps - 1) * GAP;
    }

    /** The step under the mouse, or -1. */
    int at(double mx, double my) {
        if (my < y || my >= y + CELL) return -1;
        for (int i = 0; i < steps; i++) {
            int cx = x + i * (CELL + GAP);
            if (mx >= cx && mx < cx + CELL) return i;
        }
        return -1;
    }

    void render(GuiGraphics g, int current, int hovered, double fade) {
        for (int i = 0; i < steps; i++) {
            int cx = x + i * (CELL + GAP);
            double alpha = i == current ? 1.0 : i == hovered ? 0.6 : 0.3;
            SkyDraw.disc(g, cx + CELL / 2.0, y + CELL / 2.0, DOT[steps - 2][i], SkyDraw.withAlpha(SkyPalette.FG, alpha * fade));
        }
    }

    Component tooltip(int step) {
        return Component.translatable("gui.dungeontrain.perks.zoom." + KEYS[steps - 2][step]);
    }
}
