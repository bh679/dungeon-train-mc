package games.brennan.dungeontrain.chat;

import net.minecraft.network.chat.ClickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Chat buttons that run a client action without a command.
 *
 * <p>Vanilla chat clicks can run a command and nothing else, so DT's chat buttons (Live Eye takeover,
 * vote, gift, log request, bug-response links, the memory-notice dismiss, the consent link) used to
 * run client-only commands. Those are typeable too: tab completion offers them, and NeoForge hands an
 * incomplete one (no argument) to the server, where the Free Play allowlist tainted it. So the buttons
 * now carry a {@link ClickEvent.Action#RUN_COMMAND} whose value is <b>not</b> a command:
 * {@code dungeontrain:action/arg/...}, no leading slash. A client mixin
 * ({@code ScreenChatClickMixin}) catches it before vanilla and dispatches to
 * {@code client.ChatClickActions}; a client without the mixin logs vanilla's "command without '/'"
 * error and does nothing. Nothing is sent to the server either way, and there is no command to type.
 *
 * <p>Common code (the server builds the consent link); no client imports.
 */
public final class ChatClick {

    /** Marks a click value as a DT action rather than a command. */
    public static final String PREFIX = "dungeontrain:";
    private static final String SEPARATOR = "/";

    private ChatClick() {}

    /** The click value for {@code segments}, e.g. {@code value("live-gift", "accept", id)}. */
    public static String value(String... segments) {
        if (segments.length == 0) throw new IllegalArgumentException("a chat click needs an action");
        for (String s : segments) {
            if (s == null || s.isEmpty() || s.contains(SEPARATOR) || s.contains(" ")) {
                throw new IllegalArgumentException("bad chat click segment: " + s);
            }
        }
        return PREFIX + String.join(SEPARATOR, segments);
    }

    /** A click event that runs the DT action {@code segments} on the client. */
    public static ClickEvent event(String... segments) {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, value(segments));
    }

    /** The segments of a DT click value (action first), or null when {@code value} is not one. */
    @Nullable
    public static List<String> parse(@Nullable String value) {
        if (value == null || !value.startsWith(PREFIX)) return null;
        String body = value.substring(PREFIX.length());
        if (body.isEmpty()) return List.of();
        return List.of(body.split(SEPARATOR, -1));
    }
}
