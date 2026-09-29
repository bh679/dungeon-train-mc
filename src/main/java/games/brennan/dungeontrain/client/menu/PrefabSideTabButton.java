package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.mixin.client.CreativeModeInventoryScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * Tab on the LEFT edge of the creative inventory, drawn with vanilla's own
 * creative tab sprites so it reads as a regular creative tab.
 *
 * <p>Acts as a shortcut into one of our registered {@link CreativeModeTab}s
 * — {@code onPress} calls vanilla's private {@code selectTab} via
 * {@link CreativeModeInventoryScreenAccessor}, which then drives the
 * standard items-grid / title / scrollbar / tooltip rendering.</p>
 *
 * <p>Vanilla has no left-facing tab sprite, so the {@code tab_top_*} sprite
 * is drawn transposed (mirrored across the diagonal): its top edge becomes
 * the left edge, its open edge faces the panel, and the light top-left /
 * dark bottom-right shading is preserved. Geometry mirrors
 * {@code CreativeModeInventoryScreen#renderTabButton} with x and y swapped —
 * 4px tucked under the panel, 27px pitch, icon inset (9, 5).</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PrefabSideTabButton extends AbstractButton {

    /** Visible width left of the panel; the sprite extends {@link #OVERLAP} further under it. */
    public static final int WIDTH = 28;
    public static final int HEIGHT = 26;
    /** Vertical pitch between stacked tabs — vanilla's column pitch. */
    public static final int PITCH = 27;

    private static final int OVERLAP = 4;
    private static final int SPRITE_LENGTH = WIDTH + OVERLAP;
    private static final int ICON_INSET_X = 9;
    private static final int ICON_INSET_Y = 5;
    private static final int TOOLTIP_INSET = 3;

    private final CreativeModeTab targetTab;
    private final ResourceLocation selectedSprite;
    private final ResourceLocation unselectedSprite;

    /**
     * @param row 0 for the tab flush with the panel's top corner; picks the
     *            matching vanilla sprite ({@code _1} joins the corner).
     */
    public PrefabSideTabButton(int x, int y, int row, CreativeModeTab targetTab) {
        super(x, y, WIDTH, HEIGHT, targetTab.getDisplayName());
        this.targetTab = targetTab;
        int spriteIndex = row == 0 ? 1 : 2;
        this.selectedSprite = ResourceLocation.withDefaultNamespace(
            "container/creative_inventory/tab_top_selected_" + spriteIndex);
        this.unselectedSprite = ResourceLocation.withDefaultNamespace(
            "container/creative_inventory/tab_top_unselected_" + spriteIndex);
    }

    @Override
    public void onPress() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof CreativeModeInventoryScreen screen)) return;
        ((CreativeModeInventoryScreenAccessor) screen).dungeontrain$invokeSelectTab(targetTab);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean selected = CreativeModeInventoryScreenAccessor.dungeontrain$getSelectedTab() == targetTab;
        int x0 = getX();
        int y0 = getY();

        if (selected) {
            // Drawn over the panel border so it merges, as vanilla's selected tab does.
            blitTransposed(g, selectedSprite, x0, y0);
        } else {
            // Vanilla draws unselected tabs beneath the panel; we render after
            // it, so clip the tucked-under strip instead.
            g.enableScissor(x0, y0, x0 + WIDTH, y0 + HEIGHT);
            blitTransposed(g, unselectedSprite, x0, y0);
            g.disableScissor();
        }

        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 100.0F);
        ItemStack icon = targetTab.getIconItem();
        g.renderItem(icon, x0 + ICON_INSET_X, y0 + ICON_INSET_Y);
        g.renderItemDecorations(Minecraft.getInstance().font, icon, x0 + ICON_INSET_X, y0 + ICON_INSET_Y);
        g.pose().popPose();

        if (isTooltipHovered(mouseX, mouseY)) {
            g.renderTooltip(Minecraft.getInstance().font, getMessage(), mouseX, mouseY);
        }
    }

    /** Same inset hover box vanilla uses for tab tooltips, transposed. */
    private boolean isTooltipHovered(int mouseX, int mouseY) {
        int x = getX() + TOOLTIP_INSET;
        int y = getY() + TOOLTIP_INSET;
        return mouseX >= x && mouseX < getX() + SPRITE_LENGTH - TOOLTIP_INSET
            && mouseY >= y && mouseY < getY() + HEIGHT - TOOLTIP_INSET;
    }

    /**
     * Blits a vertical (26×32) GUI sprite as a horizontal 32×26 quad with u/v
     * swapped per vertex. Vertex order matches {@code GuiGraphics#innerBlit},
     * so winding (and back-face culling) is unaffected.
     */
    private static void blitTransposed(GuiGraphics g, ResourceLocation spriteId, int x, int y) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getGuiSprites().getSprite(spriteId);
        float x1 = x;
        float x2 = x + SPRITE_LENGTH;
        float y1 = y;
        float y2 = y + HEIGHT;
        RenderSystem.setShaderTexture(0, sprite.atlasLocation());
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        Matrix4f matrix = g.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, x1, y1, 0.0F).setUv(sprite.getU0(), sprite.getV0());
        buffer.addVertex(matrix, x1, y2, 0.0F).setUv(sprite.getU1(), sprite.getV0());
        buffer.addVertex(matrix, x2, y2, 0.0F).setUv(sprite.getU1(), sprite.getV1());
        buffer.addVertex(matrix, x2, y1, 0.0F).setUv(sprite.getU0(), sprite.getV1());
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.disableBlend();
    }
}
