package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.BlockVariantCopyPickPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side middle-click shortcut for the block-variant menu's Copy button:
 * look at a variant cell in an editor plot, middle-click, and the cell's
 * variant list arrives in your hotbar as a {@code VARIANT_CLIPBOARD} item —
 * ready to right-click onto the destination cell.
 *
 * <p>The override is deliberately narrow. Editor authors use vanilla
 * pick-block constantly, so the click is only intercepted when the crosshair
 * is on a cell the server has pushed a hover list for
 * ({@link VariantHoverHudOverlay#hasHover()}, fed by
 * {@code VariantHoverPacket} and pushed only inside editor plots). On a plain
 * block, outside a plot, or in survival, vanilla pick-block runs untouched.</p>
 *
 * <p>The gate chain mirrors {@link VariantHotkeyClient.TickWatcher}; the
 * {@code mc.screen == null} guard follows the project's input-event rule —
 * {@code MouseButton.Pre} also fires for clicks inside open screens.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class VariantCopyPickClient {

    private VariantCopyPickClient() {}

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        if (mc.player == null || mc.getConnection() == null) return;
        if (TemplateBlocksHotkeyClient.inSurvival()) return;
        if (!EditorStatusHudOverlay.isActive()) return;
        if (!VariantHoverHudOverlay.hasHover()) return;

        // Suppress vanilla pick-block — this cell's copy takes the click.
        event.setCanceled(true);
        DungeonTrainNet.sendToServer(new BlockVariantCopyPickPacket());
    }
}
