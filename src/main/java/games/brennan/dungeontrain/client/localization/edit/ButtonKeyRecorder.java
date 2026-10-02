package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers which lang keys this install has seen labelling a button, and how wide that button was.
 *
 * <p>The translation editor's button preview needs both, and neither can be read off the key: only
 * a quarter of DT's {@code Button.builder} calls name their key literally, the rest build the label
 * elsewhere. So instead of guessing from source, this watches every screen as it opens and writes
 * down what was actually on its buttons — after a player has been through a screen once, its
 * labels preview at their real width.</p>
 *
 * <p>Only keys from the namespaces the editor can translate ({@link TranslationCatalog#NAMESPACES})
 * are kept, so vanilla's hundreds of labels never reach the file. Where one key labels buttons of
 * different widths the narrowest is kept: that is the one a long translation breaks first.</p>
 *
 * <p>Best-effort like the other stores in this package: an unreadable file reads as "seen nothing",
 * which costs the preview its real width and nothing else.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ButtonKeyRecorder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String FILE = "button-keys.json";
    /** Comfortably above the ~2200 gui keys DT ships; a bound, not a budget. */
    private static final int MAX_ENTRIES = 5000;
    /** What a CycleButton wraps its caption in ("Caption: value"). */
    private static final String OPTION_VALUE_KEY = "options.generic_value";
    /** Vanilla's square icon button; no label fits a button this narrow. */
    private static final int ICON_BUTTON_MAX_WIDTH = 20;

    /** key → narrowest button width seen, in GUI pixels. Null until first read off disk. */
    private static Map<String, Integer> widths;

    private ButtonKeyRecorder() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        boolean changed = false;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractButton button && !isIconOnly(button)) {
                String key = keyOf(button.getMessage());
                if (key != null) {
                    changed |= record(key, button.getWidth());
                }
            }
        }
        if (changed) {
            save();
        }
        // The whole screen too, for the button preview's full context.
        ButtonScreenLayouts.record(event.getScreen(), event.getListenersList());
    }

    /**
     * Whether {@code widget} shows an icon rather than its label, so its label — narration only —
     * is never on screen: vanilla's centred-icon button, DT's {@code …IconButton}s, and anything 20px
     * wide or narrower, which no label fits.
     */
    static boolean isIconOnly(AbstractWidget widget) {
        return widget instanceof SpriteIconButton.CenteredIcon
            || widget.getClass().getSimpleName().endsWith("IconButton")
            || widget.getWidth() <= ICON_BUTTON_MAX_WIDTH;
    }

    /** Whether {@code key} has been seen on a button. */
    public static synchronized boolean seen(String key) {
        return key != null && widths().containsKey(key);
    }

    /** The narrowest button {@code key} has been seen on, or -1 when it never has. */
    public static synchronized int widthOf(String key) {
        Integer width = key == null ? null : widths().get(key);
        return width == null ? -1 : width;
    }

    private static synchronized boolean record(String key, int width) {
        if (width <= 0) {
            return false;
        }
        Map<String, Integer> map = widths();
        Integer previous = map.get(key);
        if (previous != null && previous <= width) {
            return false;
        }
        if (previous == null && map.size() >= MAX_ENTRIES) {
            return false;
        }
        map.put(key, width);
        return true;
    }

    /** The translatable key behind a button's label, or null when it is not one of ours. */
    static String keyOf(Component message) {
        if (message == null || !(message.getContents() instanceof TranslatableContents contents)) {
            return null;
        }
        String key = contents.getKey();
        if (OPTION_VALUE_KEY.equals(key)) {
            // A CycleButton: the caption is the first argument, and that is the string being
            // translated — the value half is usually vanilla's On/Off.
            Object[] args = contents.getArgs();
            return args.length > 0 && args[0] instanceof Component caption ? keyOf(caption) : null;
        }
        return isOurs(key) ? key : null;
    }

    /** Whether any dot-separated segment of {@code key} is one of the editor's namespaces. */
    static boolean isOurs(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        for (String segment : key.split("\\.")) {
            if (TranslationCatalog.NAMESPACES.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Integer> widths() {
        if (widths == null) {
            widths = load();
        }
        return widths;
    }

    private static Path file() {
        try {
            return TranslationOverrideStore.root().resolve(FILE);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not resolve config dir — {}", e.toString());
            return null;
        }
    }

    private static Map<String, Integer> load() {
        Map<String, Integer> out = new LinkedHashMap<>();
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return out;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                if (out.size() >= MAX_ENTRIES) {
                    break;
                }
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && value.getAsInt() > 0 && isOurs(entry.getKey())) {
                    out.put(entry.getKey(), value.getAsInt());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to read {}; ignoring it — {}",
                file, e.toString());
        }
        return out;
    }

    private static synchronized void save() {
        Path file = file();
        if (file == null) {
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            widths().forEach(obj::addProperty);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to write {} — {}", file, e.toString());
        }
    }
}
