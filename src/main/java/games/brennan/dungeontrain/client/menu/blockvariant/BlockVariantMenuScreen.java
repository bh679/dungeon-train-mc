package games.brennan.dungeontrain.client.menu.blockvariant;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.client.VariantHotkeyClient;
import games.brennan.dungeontrain.client.menu.PanelIconBatch;
import games.brennan.dungeontrain.client.menu.PanelScreenHost;
import games.brennan.dungeontrain.net.BlockVariantMenuTogglePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The Z (block variant) menu drawn in screen space.
 *
 * <p>Holds no layout of its own. {@link BlockVariantMenuRenderer#drawPanel} draws it and
 * {@link BlockVariantMenuRaycast}'s hit functions pick it — the same functions the world-space
 * path uses. See {@link PanelScreenHost} for why that works. The rotation-options popup needs no
 * special handling here: it is drawn inside the root body and picked inside {@code rootHit}, so
 * it rides along with everything else.</p>
 *
 * <p>Search typing is handled here rather than by {@link BlockVariantSearchScreen}, for the same
 * reason the C menu types inline — that screen exists to stop typed letters walking the player,
 * which a world-space panel needs and a screen already has for free.</p>
 */
public final class BlockVariantMenuScreen extends PanelScreenHost {

    public BlockVariantMenuScreen() {
        super(Component.translatable("gui.dungeontrain.block_variant.title"));
    }

    /** Reciprocal of the panel's text scale, so its font lands at 1:1. */
    @Override
    protected double pxPerUnit() {
        return 1.0 / BlockVariantMenuRenderer.TEXT_SCALE;
    }

    @Override
    protected double panelWidthUnits() {
        return BlockVariantMenuRenderer.panelSize().panelW();
    }

    @Override
    protected double panelHeightUnits() {
        return BlockVariantMenuRenderer.panelSize().panelH();
    }

    @Override
    protected void drawPanel(PoseStack ps, MultiBufferSource buffer, PanelIconBatch icons) {
        BlockVariantMenuRenderer.drawPanel(ps, buffer, Minecraft.getInstance().font, icons);
    }

    @Override
    protected void hover(double localX, double localY) {
        BlockVariantMenu.setHovered(hitAt(localX, localY));
    }

    @Override
    protected void click(double localX, double localY, boolean shift) {
        BlockVariantMenu.Hit hit = hitAt(localX, localY);
        if (hit.kind() == BlockVariantMenu.CellKind.NONE) return;
        BlockVariantMenuInputHandler.dispatch(hit, shift);
    }

    /** The panel has two faces — the variant list and the Add-search list — with their own layouts. */
    private static BlockVariantMenu.Hit hitAt(double localX, double localY) {
        return BlockVariantMenu.screen() == BlockVariantMenu.Screen.ROOT
            ? BlockVariantMenuRaycast.rootHit(localX, localY)
            : BlockVariantMenuRaycast.searchHit(localX, localY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (BlockVariantMenu.screen() == BlockVariantMenu.Screen.ADD_SEARCH) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_ESCAPE -> {
                    BlockVariantMenu.backToRoot();
                    return true;
                }
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    BlockVariantMenu.backspaceSearch();
                    return true;
                }
                default -> { /* printable characters arrive via charTyped */ }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Typed characters for the search field. The allowlist matches
     * {@link BlockVariantSearchScreen} — these are registry ids, so anything outside
     * {@code [a-z0-9_:/-.]} could never match one.
     */
    @Override
    public boolean charTyped(char c, int modifiers) {
        if (BlockVariantMenu.screen() != BlockVariantMenu.Screen.ADD_SEARCH) {
            return super.charTyped(c, modifiers);
        }
        if (c >= 'A' && c <= 'Z') {
            BlockVariantMenu.appendSearch((char) (c - 'A' + 'a'));
            return true;
        }
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || c == '_' || c == ':' || c == '/' || c == '-' || c == '.') {
            BlockVariantMenu.appendSearch(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    protected KeyMapping toggleKey() {
        return VariantHotkeyClient.key();
    }

    @Override
    protected boolean stillActive() {
        return BlockVariantMenu.isActive();
    }

    /**
     * The server owns whether this menu is open, so closing asks it to close rather than just
     * dropping the screen — otherwise the client would look shut while the server still had the
     * player in the menu, and the next Z press would toggle it back the wrong way.
     */
    @Override
    protected void closeMenu() {
        DungeonTrainNet.sendToServer(new BlockVariantMenuTogglePacket(false));
    }

    @Override
    public void onClose() {
        closeMenu();
        super.onClose();
    }
}
