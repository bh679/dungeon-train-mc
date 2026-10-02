package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Component ⇄ JSON for the preview's recorders, which keep the exact message, tooltip or line a key
 * was seen in so the preview can redraw it with only that key's text changed
 * ({@link ComponentSubstitute}).
 *
 * <p>Uses the joined world's registries when there is one (hover items and the like need them), the
 * built-in ones otherwise. A component that will not convert is simply not recorded.</p>
 */
final class RecordedComponents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RecordedComponents() {}

    static String toJson(Component component) {
        try {
            return Component.Serializer.toJson(component, registries());
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not record a component — {}", e.toString());
            return null;
        }
    }

    static Component fromJson(String json) {
        try {
            return Component.Serializer.fromJson(json, registries());
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not read a recorded component — {}", e.toString());
            return null;
        }
    }

    private static HolderLookup.Provider registries() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.level != null ? mc.level.registryAccess()
            : RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }
}
