package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers what the screens carrying DT's button labels look like, so the button preview can show a
 * label in its "Full Context": the real screen it sits on, with every other widget where it was.
 *
 * <p>The companion of {@link ButtonKeyRecorder}, fed by the same {@code ScreenEvent.Init.Post}: each
 * screen showing at least one of the editor's keys is written down as its size, title and widgets
 * (position, size, label, and the label's key when it is one of ours). A key points at the last
 * layout it was seen on. Text a screen draws itself, outside any widget, is not captured.</p>
 *
 * <p>Best-effort like the other stores here: an unreadable file reads as "seen nothing", which costs
 * the preview its full context and nothing else.</p>
 */
public final class ButtonScreenLayouts {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    private static final String FILE = "button-screens.json";
    /** Bounds the file: a screen re-laid out at many window sizes keeps only its latest few. */
    private static final int MAX_LAYOUTS = 400;
    private static final String OPTION_VALUE_KEY = "options.generic_value";

    /**
     * One widget as it was drawn.
     *
     * @param key    the editor key behind its label, or null when the label is not one of ours
     * @param suffix for an option button ("Caption: value"), the value half; else empty
     */
    public record Widget(int x, int y, int w, int h, String key, String text, boolean button, String suffix) {}

    /** A screen as it was laid out, in GUI pixels at GUI scale {@code scale}. */
    public record Layout(String title, int width, int height, int scale, List<Widget> widgets) {}

    /** What is kept on disk: layouts by id, and the layout each key was last seen on. */
    private static final class Store {
        Map<String, Layout> layouts = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
    }

    private static Store store;

    private ButtonScreenLayouts() {}

    /** Write down {@code screen}'s layout if it carries any of the editor's keys. */
    static void record(Screen screen, List<GuiEventListener> listeners) {
        if (screen == null || screen instanceof TranslationPreviewScreen) {
            return; // the preview's own buttons would only describe the preview
        }
        List<Widget> widgets = new ArrayList<>();
        Set<String> ours = new HashSet<>();
        for (GuiEventListener listener : listeners) {
            if (listener instanceof AbstractWidget widget && widget.visible) {
                Widget w = widgetOf(widget);
                widgets.add(w);
                if (w.key() != null && w.button()) {
                    ours.add(w.key());
                }
            }
        }
        if (ours.isEmpty()) {
            return;
        }
        Layout layout = new Layout(screen.getTitle().getString(), screen.width, screen.height,
            (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale()), List.copyOf(widgets));
        String id = screen.getClass().getSimpleName() + "#" + Integer.toHexString(layout.hashCode());
        synchronized (ButtonScreenLayouts.class) {
            Store s = store();
            boolean changed = s.layouts.put(id, layout) == null;
            for (String key : ours) {
                changed |= !id.equals(s.keys.put(key, id));
            }
            if (changed) {
                prune(s);
                save(s);
            }
        }
    }

    /** The screen {@code key} was last seen labelling a button on, or null. */
    public static synchronized Layout layoutFor(String key) {
        if (key == null) {
            return null;
        }
        String id = store().keys.get(key);
        return id == null ? null : store().layouts.get(id);
    }

    private static Widget widgetOf(AbstractWidget widget) {
        Component message = widget.getMessage();
        String suffix = "";
        if (message != null && message.getContents() instanceof TranslatableContents contents
            && OPTION_VALUE_KEY.equals(contents.getKey()) && contents.getArgs().length > 1) {
            Object value = contents.getArgs()[1];
            suffix = value instanceof Component c ? c.getString() : String.valueOf(value);
        }
        return new Widget(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(),
            ButtonKeyRecorder.keyOf(message), message == null ? "" : message.getString(),
            widget instanceof AbstractButton, suffix);
    }

    /** Drop layouts no key points at, then the oldest, down to {@link #MAX_LAYOUTS}. */
    private static void prune(Store s) {
        Set<String> live = new HashSet<>(s.keys.values());
        s.layouts.keySet().removeIf(id -> !live.contains(id));
        while (s.layouts.size() > MAX_LAYOUTS) {
            String oldest = s.layouts.keySet().iterator().next();
            s.layouts.remove(oldest);
            s.keys.values().removeIf(oldest::equals);
        }
    }

    private static Store store() {
        if (store == null) {
            store = load();
        }
        return store;
    }

    private static Path file() {
        try {
            return TranslationOverrideStore.root().resolve(FILE);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not resolve config dir — {}", e.toString());
            return null;
        }
    }

    private static Store load() {
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return new Store();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
            Store s = GSON.fromJson(root, Store.class);
            if (s == null || s.layouts == null || s.keys == null) {
                return new Store();
            }
            s.keys.values().removeIf(id -> !s.layouts.containsKey(id));
            return s;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to read {}; ignoring it — {}", file, e.toString());
            return new Store();
        }
    }

    private static void save(Store s) {
        Path file = file();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(s), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to write {} — {}", file, e.toString());
        }
    }
}
