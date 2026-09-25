package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Re-arms {@link EditorPanelFacing} whenever the player teleports or leaves the world, so the
 * editor panels turn to face them from where they land.
 *
 * <p>A teleport is read off the client as a jump of more than {@link #TELEPORT_BLOCKS} in one
 * tick, or a dimension change — every editor landing ({@code EditorPlotArrival}, nav-row clicks,
 * Go here) is a server teleport, and none of them is a walk. Very fast flight could trip it too,
 * which only re-faces the panels.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EditorPanelFacingEvents {

    /** Movement in one tick past this counts as a teleport. */
    static final double TELEPORT_BLOCKS = 8.0;

    private static Vec3 lastPos;
    private static ResourceKey<Level> lastDim;

    private EditorPanelFacingEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            lastPos = null;
            lastDim = null;
            return;
        }
        Vec3 pos = player.position();
        ResourceKey<Level> dim = player.level().dimension();
        if (isTeleport(lastPos, lastDim, pos, dim)) EditorPanelFacing.clearAll();
        lastPos = pos;
        lastDim = dim;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        EditorPanelFacing.clearAll();
        lastPos = null;
        lastDim = null;
    }

    /**
     * A panel's {@code ↻} button was clicked: face the camera, or with shift held snap to
     * {@code gridDefault}. Shift is read from GLFW — the world-space panels have no Screen, so
     * {@code Screen.hasShiftDown()} is unreliable here (see {@link MenuClickModifiers}). The caller
     * plays its own click sound, as it does for every other cell.
     */
    public static void onButton(BlockPos key, Vec3 anchor, Vec3[] gridDefault) {
        Minecraft mc = Minecraft.getInstance();
        if (shiftDown(mc)) {
            EditorPanelFacing.reset(key, gridDefault);
        } else {
            EditorPanelFacing.faceNow(key, anchor, mc.gameRenderer.getMainCamera().getPosition());
        }
    }

    private static boolean shiftDown(Minecraft mc) {
        long win = mc.getWindow().getWindow();
        return GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    /** True when the move from ({@code fromPos}, {@code fromDim}) to ({@code pos}, {@code dim}) is a teleport. */
    static boolean isTeleport(Vec3 fromPos, Object fromDim, Vec3 pos, Object dim) {
        if (fromPos == null) return false;
        if (fromDim != null && !fromDim.equals(dim)) return true;
        return fromPos.distanceToSqr(pos) > TELEPORT_BLOCKS * TELEPORT_BLOCKS;
    }
}
