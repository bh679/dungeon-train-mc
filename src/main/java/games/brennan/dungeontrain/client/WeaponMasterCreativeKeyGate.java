package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.slf4j.Logger;

/**
 * Keeps YDM's Weapon Master settings key quiet in Creative, where its default (V) is also the
 * editor's template-blocks key ({@link TemplateBlocksHotkeyClient}). Weapon Master opens its
 * screen from its own {@code InputEvent.Key} handler whenever its key is down; this runs first
 * ({@link EventPriority#HIGHEST}) and lets the key go. Survival and Adventure are untouched.
 *
 * <p>Weapon Master is not a compile dependency: its key is found by name among the game's key
 * mappings, and nothing happens when the mod is not installed.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class WeaponMasterCreativeKeyGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String MOD_ID = "weaponmaster_ydm";
    static final String KEY_NAME = "key.weaponmaster_ydm.opengui";

    private static Boolean installed;
    private static KeyMapping key;
    private static boolean lookupFailed;

    private WeaponMasterCreativeKeyGate() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKey(InputEvent.Key event) {
        releaseInCreative();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        releaseInCreative();
    }

    private static void releaseInCreative() {
        if (!inCreative()) {
            return;
        }
        KeyMapping mapping = key();
        if (mapping == null) {
            return;
        }
        mapping.setDown(false);
        while (mapping.consumeClick()) {
            // discard
        }
    }

    private static boolean inCreative() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE;
    }

    private static KeyMapping key() {
        if (key != null || lookupFailed) {
            return key;
        }
        if (installed == null) {
            installed = ModList.get().isLoaded(MOD_ID);
        }
        if (!installed) {
            lookupFailed = true;
            return null;
        }
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            if (KEY_NAME.equals(mapping.getName())) {
                key = mapping;
                return key;
            }
        }
        lookupFailed = true;
        LOGGER.warn("[DungeonTrain] Weapon Master is installed but its '{}' key was not found; "
            + "its settings key is not held back in Creative.", KEY_NAME);
        return null;
    }
}
