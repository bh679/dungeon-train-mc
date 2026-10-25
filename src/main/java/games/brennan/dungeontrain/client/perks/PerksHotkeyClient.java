package games.brennan.dungeontrain.client.perks;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.TemplateBlocksHotkeyClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * {@code P} opens the perks constellation. Vanilla stops polling bindings while a Screen is up, so
 * {@link PerksScreen} matches the binding itself to close on a second press.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PerksHotkeyClient {

    public static final String NAME = "key." + DungeonTrain.MOD_ID + ".perks";

    private static final KeyMapping KEY = new KeyMapping(
        NAME,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_P,
        TemplateBlocksHotkeyClient.CATEGORY
    );

    private PerksHotkeyClient() {}

    public static KeyMapping key() {
        return KEY;
    }

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(KEY);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        while (KEY.consumeClick()) {
            if (mc.screen == null) PerksScreen.open(false);
        }
    }
}
