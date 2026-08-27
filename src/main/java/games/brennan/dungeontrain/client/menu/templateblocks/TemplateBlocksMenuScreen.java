package games.brennan.dungeontrain.client.menu.templateblocks;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.client.TemplateBlocksHotkeyClient;
import games.brennan.dungeontrain.client.menu.PanelScreenHost;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TemplateBlocksMenuTogglePacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;

/**
 * The V (template blocks) menu drawn in screen space.
 *
 * <p>Holds no layout of its own. {@link TemplateBlocksMenuRenderer#drawPanel} draws it and
 * {@link TemplateBlocksMenuRaycast#hitTest} picks it — the same two functions the world-space
 * path uses — so the two modes cannot disagree about where a row is. See {@link PanelScreenHost}
 * for why that works.</p>
 */
public final class TemplateBlocksMenuScreen extends PanelScreenHost {

    public TemplateBlocksMenuScreen() {
        super(Component.translatable("gui.dungeontrain.template_blocks.title"));
    }

    /** Reciprocal of the panel's text scale, so its font lands at 1:1. */
    @Override
    protected double pxPerUnit() {
        return 1.0 / TemplateBlocksMenuRenderer.TEXT_SCALE;
    }

    @Override
    protected double panelWidthUnits() {
        return TemplateBlocksMenuRenderer.panelSize().panelW();
    }

    @Override
    protected double panelHeightUnits() {
        return TemplateBlocksMenuRenderer.panelSize().panelH();
    }

    @Override
    protected void drawPanel(PoseStack ps, MultiBufferSource buffer) {
        TemplateBlocksMenuRenderer.drawPanel(ps, buffer, Minecraft.getInstance().font, true);
    }

    @Override
    protected void hover(double localX, double localY) {
        TemplateBlocksMenu.setHovered(TemplateBlocksMenuRaycast.hitTest(localX, localY));
    }

    @Override
    protected void click(double localX, double localY, boolean shift) {
        TemplateBlocksMenu.Hit hit = TemplateBlocksMenuRaycast.hitTest(localX, localY);
        if (hit.kind() == TemplateBlocksMenu.CellKind.NONE) return;
        TemplateBlocksMenuInputHandler.dispatch(hit);
    }

    @Override
    protected KeyMapping toggleKey() {
        return TemplateBlocksHotkeyClient.key();
    }

    @Override
    protected boolean stillActive() {
        return TemplateBlocksMenu.isActive();
    }

    /**
     * The server owns whether this menu is open, so closing asks it to close rather than just
     * dropping the screen — otherwise the client would look shut while the server still had the
     * player in the menu, and the next V press would toggle it back the wrong way.
     */
    @Override
    protected void closeMenu() {
        DungeonTrainNet.sendToServer(new TemplateBlocksMenuTogglePacket(false));
    }

    @Override
    public void onClose() {
        closeMenu();
        super.onClose();
    }
}
