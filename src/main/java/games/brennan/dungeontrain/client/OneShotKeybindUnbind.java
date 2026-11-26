package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Clears another mod's keybindings <b>once</b>, so DT can ship that mod with no key of its own
 * while never fighting a player who later binds one. Mappings are found by {@link KeyMapping}
 * name, never by class, so the target mod stays a soft reference.
 *
 * <p>A marker file is written after the first attempt that finds a matching mapping; while it
 * exists nothing is touched again. When no mapping matches (the mod is absent) no marker is
 * written, so a later install is still defaulted on its first launch.</p>
 */
final class OneShotKeybindUnbind {

    private static final Logger LOGGER = LogUtils.getLogger();

    private OneShotKeybindUnbind() {}

    /** Mappings whose name starts with {@code prefix}. */
    static Predicate<String> prefix(String prefix) {
        return name -> name.startsWith(prefix);
    }

    /** The mapping named exactly {@code name}. */
    static Predicate<String> exactly(String name) {
        return name::equals;
    }

    /**
     * Unbind every mapping whose name matches, unless {@code marker} already exists.
     *
     * @param label the mod's name, for the log and the marker text
     */
    static void unbindOnce(Predicate<String> names, Path marker, String label) throws Exception {
        if (Files.exists(marker)) return; // already handled once: never fight the player afterwards

        Minecraft mc = Minecraft.getInstance();
        List<KeyMapping> matches = new ArrayList<>();
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (names.test(mapping.getName())) {
                matches.add(mapping);
            }
        }
        if (matches.isEmpty()) {
            return; // mod not installed: retry on a future launch, no marker yet
        }

        int cleared = 0;
        for (KeyMapping mapping : matches) {
            if (mapping.isUnbound()) continue;
            mapping.setKey(InputConstants.UNKNOWN);
            cleared++;
            LOGGER.info("[DungeonTrain] Cleared {} keybinding '{}'", label, mapping.getName());
        }
        if (cleared > 0) {
            KeyMapping.resetMapping();
        }

        // Mark handled BEFORE saving so these are only ever defaulted once, even across restarts.
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, label + " keybindings defaulted to unbound once. Delete to re-arm.\n");

        if (cleared > 0) {
            mc.options.save();
        }
    }
}
