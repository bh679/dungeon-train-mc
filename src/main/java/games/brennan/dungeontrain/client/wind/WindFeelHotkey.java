package games.brennan.dungeontrain.client.wind;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * K cycles the "you are being pushed" wind effects ({@link WindFeelMode}) so the combos can be compared in game.
 * Dev builds only: release builds never register the key and stay on {@link WindFeelMode#DEFAULT}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WindFeelHotkey {

    /** How long the big "switched to" label stays up after a press. */
    static final long FLASH_MILLIS = 2000L;

    private static final KeyMapping KEY = new KeyMapping(
            "key." + DungeonTrain.MOD_ID + ".wind_feel_cycle",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            "key.categories." + DungeonTrain.MOD_ID);

    private static volatile boolean registered = false;
    private static volatile long flashUntil = 0L;

    private WindFeelHotkey() {}

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        if (!DungeonTrain.isDevBuild()) return;
        event.register(KEY);
        registered = true;
    }

    /** True while the "switched to" label should show. */
    static boolean flashing() {
        return Util.getMillis() < flashUntil;
    }

    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class Watcher {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (!registered) return;
            while (KEY.consumeClick()) {
                WindFeelMode.cycle();
                flashUntil = Util.getMillis() + FLASH_MILLIS;
            }
        }
    }
}
