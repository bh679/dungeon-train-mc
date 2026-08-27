package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;

/**
 * Draws one of the editor's world-space panels as a GUI {@link Screen} instead, without
 * duplicating a single line of its layout.
 *
 * <h2>Why this works</h2>
 *
 * <p>Every editor panel is already laid out in <em>panel-local</em> units — x right, y up,
 * origin at the panel's centre — and only its outermost transform makes it world-space. The
 * hit-tests are the same shape: {@code hitTest(localX, localY)} is a pure function of those
 * same units, with the camera raycast above it doing nothing but produce the pair.</p>
 *
 * <p>So a screen-space mode is not a second renderer. It is a different outer transform
 * (panel units to GUI pixels rather than a quad at a world anchor) and a different source for
 * the {@code (x, y)} probe (the mouse rather than the camera ray). Subclasses supply the panel's
 * size, its existing draw body, and its existing hit function; everything between is shared with
 * the world-space path, which is why hover and click cannot drift apart between the two modes.</p>
 *
 * <h2>The Y mirror</h2>
 *
 * <p>Screen y grows downward and panel y grows upward, so the transform mirrors Y. That is
 * invisible for quads and self-correcting for text (the panels' text helpers already carry
 * their own {@code -scale} in Y, which the outer mirror cancels back to upright at 1:1 — which
 * is also why {@link #pxPerUnit()} should be the reciprocal of the panel's text scale). It is
 * <em>not</em> invisible for item icons, whose models would render upside down and back-face
 * culled, so those need compensating at their own draw call — see the {@code screenspace} flag
 * threaded into the panels' icon helpers.</p>
 */
public abstract class PanelScreenHost extends Screen {

    /**
     * A light dim over the world. Deliberately not a blur: the panel describes the build the
     * author is looking at, and obscuring the build defeats the point. Matches
     * {@link CommandMenuGuiScreen}.
     */
    private static final int SCREEN_DIM = 0x80101010;

    /** Last frame's placement, needed to map the mouse back into panel units. */
    private PanelSpaceMapping mapping = new PanelSpaceMapping(1.0, 0.0, 0.0);

    protected PanelScreenHost(Component title) {
        super(title);
    }

    // ------------------------------------------------------------------
    // Subclass contract
    // ------------------------------------------------------------------

    /**
     * Pixels per panel-local unit at full size. Return the reciprocal of the panel's own text
     * scale so its font lands at 1:1 — a panel written against {@code TEXT_SCALE = 0.012}
     * returns {@code 1 / 0.012}. Shrunk automatically when the panel would not fit the window.
     */
    protected abstract double pxPerUnit();

    /** The panel's full width in panel-local units, from the same math its draw body uses. */
    protected abstract double panelWidthUnits();

    /** The panel's full height in panel-local units, from the same math its draw body uses. */
    protected abstract double panelHeightUnits();

    /** The panel's existing world-space draw body, called under the GUI transform. */
    protected abstract void drawPanel(PoseStack ps, MultiBufferSource buffer);

    /** Resolve and store the hovered cell for a cursor at these panel-local coordinates. */
    protected abstract void hover(double localX, double localY);

    /** Dispatch a click at these panel-local coordinates. */
    protected abstract void click(double localX, double localY, boolean shift);

    /** False once the server has closed the menu, so the screen can dismiss itself. */
    protected abstract boolean stillActive();

    /** Tell the menu to close — usually a toggle packet to the server. */
    protected abstract void closeMenu();

    // ------------------------------------------------------------------
    // Screen
    // ------------------------------------------------------------------

    /** The world keeps ticking — an author needs the train moving while they edit. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        gg.fill(0, 0, this.width, this.height, SCREEN_DIM);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        if (!stillActive()) {
            onClose();
            return;
        }
        super.render(gg, mouseX, mouseY, partialTick);

        layout();
        hover(localX(mouseX), localY(mouseY));

        PoseStack ps = gg.pose();
        ps.pushPose();
        ps.translate(mapping.centreX(), mapping.centreY(), 0.0);
        // Y is mirrored to turn the panel's y-up into the screen's y-down; see the class note.
        // PanelSpaceMapping#localY is the exact inverse of this, which is what keeps hover and
        // click over the same cell.
        float px = (float) mapping.pxPerUnit();
        ps.scale(px, -px, px);
        drawPanel(ps, gg.bufferSource());
        ps.popPose();
        // The panels draw through their own RenderTypes rather than GuiGraphics' primitives,
        // so nothing has been flushed for us.
        gg.flush();
    }

    /** Centre the panel and pick a scale that fits. See {@link PanelSpaceMapping#fit}. */
    private void layout() {
        mapping = PanelSpaceMapping.fit(this.width, this.height, CommandMenuLayout.HOTBAR_RESERVE,
            panelWidthUnits(), panelHeightUnits(), pxPerUnit());
    }

    /** Screen pixel x to panel-local x. */
    protected double localX(double mouseX) {
        return mapping.localX(mouseX);
    }

    /** Screen pixel y to panel-local y. */
    protected double localY(double mouseY) {
        return mapping.localY(mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            click(localX(mouseX), localY(mouseY), hasShiftDown());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * The wheel falls through to the hotbar, via the same {@code Inventory.swapPaint} vanilla's
     * own mouse handler calls, so wrapping and direction match the game exactly. These panels
     * wrap into columns rather than scrolling, so there is no list here competing for the wheel.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.getInventory().swapPaint(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (hotbarKey(keyCode, scanCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 1-9 select a hotbar slot, exactly as they would with no screen open. */
    private boolean hotbarKey(int keyCode, int scanCode) {
        if (this.minecraft == null || this.minecraft.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (this.minecraft.options.keyHotbarSlots[i].matches(keyCode, scanCode)) {
                this.minecraft.player.getInventory().selected = i;
                return true;
            }
        }
        return false;
    }
}
