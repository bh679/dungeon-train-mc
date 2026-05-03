package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.ManualSpawnRequestPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * J-keybind hotkey for stepping the train appender forward by one group.
 * Sends a {@link ManualSpawnRequestPacket} on each fresh press (no auto-
 * repeat); the server consumes the request once via
 * {@link games.brennan.dungeontrain.train.TrainCarriageAppender#requestManualSpawn()}.
 *
 * <p>Pairs with {@code TrainCarriageAppender.MANUAL_MODE = true}: while
 * manual mode is on, this is the only way new carriage groups appear, so
 * the user can debug spawn-by-spawn alongside the wireframe-preview
 * overlay rendered by {@link CarriageGroupGapDebugRenderer}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ManualSpawnHotkeyClient {

    public static final String CATEGORY = "key.categories." + DungeonTrain.MOD_ID;
    public static final String NAME = "key." + DungeonTrain.MOD_ID + ".manual_spawn";

    private static final KeyMapping KEY = new KeyMapping(
        NAME,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_J,
        CATEGORY
    );

    private ManualSpawnHotkeyClient() {}

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(KEY);
    }

    /**
     * Edge-triggered tick watcher: fires {@link ManualSpawnRequestPacket}
     * exactly once per fresh press by polling {@link KeyMapping#consumeClick}
     * (which auto-decrements the queued-click counter so a long press is
     * still one logical press).
     */
    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class ManualSpawnTickWatcher {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (Minecraft.getInstance().getConnection() == null) return;
            while (KEY.consumeClick()) {
                DungeonTrainNet.sendToServer(new ManualSpawnRequestPacket());
            }
        }
    }
}
