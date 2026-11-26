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
        // Kept out of the Controls list until perks launch (PerksGate). An unregistered mapping still
        // fires (vanilla keys clicks off every constructed mapping), so P works in a perks test world.
        if (!games.brennan.dungeontrain.perks.PerksGate.LAUNCHED) return;
        event.register(KEY);
    }

    /**
     * Pre, not Post: vanilla's Social Interactions binding is also {@code P}, and in a world opened to
     * LAN (how a perks test world gets cheats) vanilla's keybind pass would open that screen first and
     * leave this one shut out. With perks on, the perks menu takes the press and the shared click is
     * drained from the social binding.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        boolean pressed = false;
        while (KEY.consumeClick()) pressed = true;
        if (!pressed || mc.screen != null || !games.brennan.dungeontrain.perks.PerksGate.isOnClient()) return;
        KeyMapping social = mc.options.keySocialInteractions;
        if (social.same(KEY)) {
            while (social.consumeClick()) {
                // the same press, meant for the perks menu
            }
        }
        PerksScreen.open(false);
    }
}
