package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.chat.ChatClick;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Client side of {@link ChatClick}: the actions a DT chat button can run, by name. Each feature
 * registers its handlers from {@code DungeonTrainClient.clientSetup}; {@code ScreenChatClickMixin}
 * calls {@link #handle} for every chat click before vanilla sees it.
 *
 * <p>A handler gets the segments after the action name and runs on the client thread. A DT click
 * with no handler is swallowed (logged), never passed on to vanilla: there is nothing a command
 * could do with it, and the server must never see it.
 */
public final class ChatClickActions {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Consumer<List<String>>> HANDLERS = new ConcurrentHashMap<>();

    private ChatClickActions() {}

    /** Register the handler for {@code action}; a second registration replaces the first. */
    public static void register(String action, Consumer<List<String>> handler) {
        HANDLERS.put(action, handler);
    }

    /**
     * Run a DT chat click, if {@code event} is one.
     *
     * @return true when the click was DT's (handled or not) and vanilla must not act on it
     */
    public static boolean handle(@Nullable ClickEvent event) {
        if (event == null || event.getAction() != ClickEvent.Action.RUN_COMMAND) return false;
        return dispatch(event.getValue(), Minecraft.getInstance()::execute);
    }

    /** {@link #handle} without the client: {@code run} receives the handler call to schedule. Pure for the tests. */
    static boolean dispatch(@Nullable String value, Consumer<Runnable> run) {
        List<String> parts = ChatClick.parse(value);
        if (parts == null) return false;
        if (parts.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Empty chat click {}", value);
            return true;
        }
        Consumer<List<String>> handler = HANDLERS.get(parts.get(0));
        if (handler == null) {
            LOGGER.warn("[DungeonTrain] No handler for chat click {}", value);
            return true;
        }
        List<String> args = parts.subList(1, parts.size());
        run.accept(() -> {
            try {
                handler.accept(args);
            } catch (Exception ex) {
                LOGGER.warn("[DungeonTrain] Chat click {} failed: {}", value, ex.toString());
            }
        });
        return true;
    }

    /** Test seam. */
    static void clear() {
        HANDLERS.clear();
    }
}
