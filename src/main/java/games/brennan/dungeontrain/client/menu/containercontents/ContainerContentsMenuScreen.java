package games.brennan.dungeontrain.client.menu.containercontents;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.client.ContainerHotkeyClient;
import games.brennan.dungeontrain.client.menu.PanelIconBatch;
import games.brennan.dungeontrain.client.menu.PanelScreenHost;
import games.brennan.dungeontrain.net.ContainerContentsMenuTogglePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The C (container contents) menu drawn in screen space.
 *
 * <p>Holds no layout of its own. {@link ContainerContentsMenuRenderer#drawPanel} draws it and
 * {@link ContainerContentsMenuRaycast}'s hit functions pick it — the same functions the
 * world-space path uses. See {@link PanelScreenHost} for why that works.</p>
 *
 * <p>Search typing is handled here rather than by {@link ContainerContentsSearchScreen}. That
 * screen exists because the world-space panel has no screen of its own, so it needs an invisible
 * one to stop typed letters from walking the player; opening it over <em>this</em> screen would
 * replace the panel and leave the author typing at nothing. We are already the active screen, so
 * vanilla keybindings are suppressed for free — the same reason the X menu types inline.</p>
 */
public final class ContainerContentsMenuScreen extends PanelScreenHost {

    public ContainerContentsMenuScreen() {
        super(Component.translatable("gui.dungeontrain.container_contents.title"));
    }

    /** Reciprocal of the panel's text scale, so its font lands at 1:1. */
    @Override
    protected double pxPerUnit() {
        return 1.0 / ContainerContentsMenuRenderer.TEXT_SCALE;
    }

    @Override
    protected double panelWidthUnits() {
        return ContainerContentsMenuRenderer.panelSize().panelW();
    }

    @Override
    protected double panelHeightUnits() {
        return ContainerContentsMenuRenderer.panelSize().panelH();
    }

    @Override
    protected void drawPanel(PoseStack ps, MultiBufferSource buffer, PanelIconBatch icons) {
        ContainerContentsMenuRenderer.drawPanel(ps, buffer, Minecraft.getInstance().font, icons);
    }

    @Override
    protected void hover(double localX, double localY) {
        ContainerContentsMenu.setHovered(hitAt(localX, localY));
    }

    @Override
    protected void click(double localX, double localY, boolean shift) {
        ContainerContentsMenu.Hit hit = hitAt(localX, localY);
        if (hit.kind() == ContainerContentsMenu.CellKind.NONE) return;
        ContainerContentsMenuInputHandler.dispatch(hit, shift);
    }

    /** The panel has two faces — the entry list and the Add-search list — with their own layouts. */
    private static ContainerContentsMenu.Hit hitAt(double localX, double localY) {
        return ContainerContentsMenu.screen() == ContainerContentsMenu.Screen.ROOT
            ? ContainerContentsMenuRaycast.rootHit(localX, localY)
            : ContainerContentsMenuRaycast.searchHit(localX, localY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ContainerContentsMenu.screen() == ContainerContentsMenu.Screen.ADD_SEARCH) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_ESCAPE -> {
                    ContainerContentsMenu.backToRoot();
                    return true;
                }
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    ContainerContentsMenu.backspaceSearch();
                    return true;
                }
                default -> { /* printable characters arrive via charTyped */ }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Typed characters for the search field. The allowlist matches
     * {@link ContainerContentsSearchScreen} exactly — these are registry ids, so anything
     * outside {@code [a-z0-9_:/-.]} could never match one.
     */
    @Override
    public boolean charTyped(char c, int modifiers) {
        if (ContainerContentsMenu.screen() != ContainerContentsMenu.Screen.ADD_SEARCH) {
            return super.charTyped(c, modifiers);
        }
        if (c >= 'A' && c <= 'Z') {
            ContainerContentsMenu.appendSearch((char) (c - 'A' + 'a'));
            return true;
        }
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || c == '_' || c == ':' || c == '/' || c == '-' || c == '.') {
            ContainerContentsMenu.appendSearch(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    protected KeyMapping toggleKey() {
        return ContainerHotkeyClient.key();
    }

    @Override
    protected boolean stillActive() {
        return ContainerContentsMenu.isActive();
    }

    /**
     * The server owns whether this menu is open, so closing asks it to close rather than just
     * dropping the screen — otherwise the client would look shut while the server still had the
     * player in the menu, and the next C press would toggle it back the wrong way.
     */
    @Override
    protected void closeMenu() {
        DungeonTrainNet.sendToServer(new ContainerContentsMenuTogglePacket(false));
    }

    @Override
    public void onClose() {
        closeMenu();
        super.onClose();
    }
}
