package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client game-bus hooks for the void band's atmosphere:
 * <ul>
 *   <li>{@link ViewportEvent.ComputeFogColor} — darkens fog toward the End's look
 *       (~15% brightness) as the void intensity {@code t} rises, so the horizon
 *       blackens out under the End-sky overlay.</li>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} — clears the synced band state
 *       so it never leaks into the next world.</li>
 * </ul>
 * The sky overlay itself is drawn by {@link VoidSkyRenderer} from a render mixin.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class VoidSkyEvents {

    private VoidSkyEvents() {}

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double t = ClientVoidBand.voidAt(event.getCamera().getPosition().x);
        if (t <= 0.0) return;
        float f = (float) (1.0 - 0.85 * Math.min(1.0, t)); // → ~0.15× at full void (End fog)
        event.setRed(event.getRed() * f);
        event.setGreen(event.getGreen() * f);
        event.setBlue(event.getBlue() * f);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientVoidBand.reset();
    }
}
