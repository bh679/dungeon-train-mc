package games.brennan.dungeontrain.client.live.deathpages;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * What the Live Eye death pages borrow from {@code NarrativeDeathScreen}, so they draw with its
 * fade, tiles, bevels and train instead of copies of them.
 */
public interface LiveDeathChrome {

    Font font();

    /** {@code argb} at the page transition's current alpha. */
    int fade(int argb);

    /** The transition's current alpha, 0..1, for things drawn without a colour (skin faces). */
    float alpha();

    /** The page's train across the content column. */
    void train(GuiGraphics g, int left, int w, int y);

    /** One stat tile: {@code value} over {@code label}, centred on {@code centerX}, {@code cw} wide. */
    void cell(GuiGraphics g, int centerX, int y, String value, Component label, int cw);

    /** A bevel button; returns nothing, the page keeps its own hit rect. */
    void bevel(GuiGraphics g, int x, int y, int w, int h, Component text, Style style);

    enum Style { PLAIN, PURPLE }
}
