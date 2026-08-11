package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Pause-menu action button used by {@link games.brennan.dungeontrain.client.PauseMenuLayoutHandler}
 * to build the "Abandon This Run" / "Exit to Title" / "Quit Game" cluster.
 *
 * <p>Two behaviours layered on a vanilla {@link Button}:</p>
 * <ul>
 *   <li><b>Tint</b> — the vanilla button sprite is multiplied by
 *       {@code (tintR, tintG, tintB)} before blit (red for Abandon, white/grey
 *       for Exit, dark-grey for Quit), then colour is restored to white so the
 *       label stays crisp. Mirrors {@link DarkTintedButton}.</li>
 *   <li><b>Shift-gated visibility</b> — the button shows only when the live
 *       Shift state matches {@code visibleWhenShift}. The single red Abandon
 *       button ({@code visibleWhenShift = false}) and the two Shift-revealed
 *       buttons ({@code visibleWhenShift = true}) occupy the same slot and swap
 *       as Shift is pressed/released. The toggle is applied each frame by
 *       {@link games.brennan.dungeontrain.client.PauseMenuLayoutHandler}'s
 *       {@code ScreenEvent.Render.Pre} handler (the {@code render} method is
 *       {@code final} and can't be overridden); {@code visible} gates both
 *       rendering and click handling (vanilla {@code AbstractWidget} checks
 *       {@code active && visible} in {@code mouseClicked}).</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class PauseMenuActionButton extends Button {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted")
    );

    private final float tintR;
    private final float tintG;
    private final float tintB;
    private final boolean visibleWhenShift;

    public PauseMenuActionButton(int x, int y, int width, int height, Component message,
                                 float tintR, float tintG, float tintB,
                                 boolean visibleWhenShift, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.tintR = tintR;
        this.tintG = tintG;
        this.tintB = tintB;
        this.visibleWhenShift = visibleWhenShift;
    }

    /** Whether this button should be visible when Shift is held (vs. when it isn't). */
    public boolean visibleWhenShift() {
        return this.visibleWhenShift;
    }

    /**
     * The tint applied to the button sprite, read once per frame. Fixed at construction for the
     * pause-menu cluster, but overridable for buttons whose colour <em>is</em> their state readout
     * — {@link games.brennan.dungeontrain.client.builder.BuilderMirrorButton} relights itself on
     * click, and no {@code init} pass runs between the click and the next frame.
     */
    protected float tintR() {
        return this.tintR;
    }

    protected float tintG() {
        return this.tintG;
    }

    protected float tintB() {
        return this.tintB;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        g.setColor(this.tintR(), this.tintG(), this.tintB(), this.alpha);
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        g.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()),
                this.getX(), this.getY(), this.getWidth(), this.getHeight());
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
        this.renderString(g, mc.font, textColor | Mth.ceil(this.alpha * 255.0F) << 24);
    }
}
