package games.brennan.dungeontrain.client.twitch;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a Twitch handle in something a player typed: {@code https://www.twitch.tv/steve_streams},
 * {@code twitch.tv/steve_streams}, {@code @steve_streams}, or — only when the whole message is one
 * such token and the caller asked for bare names — {@code steve_streams}. The login is Twitch's own
 * shape (3–25 of {@code [a-z0-9_]}) and never one of twitch.tv's site routes. Pure, so
 * {@code TwitchHandleTest} covers it without a client.
 */
public final class TwitchHandle {

    private static final Pattern URL = Pattern.compile("(?i)(?:https?://)?(?:www\\.|m\\.)?twitch\\.tv/([A-Za-z0-9_]{3,25})(?![A-Za-z0-9_])");
    private static final Pattern AT = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z0-9_]{3,25})(?![A-Za-z0-9_])");
    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9_]{3,25}");

    /** twitch.tv first-path segments that are site routes, not channel logins — the relay's own list. */
    static final Set<String> RESERVED = Set.of(
            "videos", "clip", "clips", "directory", "settings", "subscriptions", "following", "friends",
            "inventory", "wallet", "drops", "prime", "turbo", "downloads", "jobs", "p", "u", "team", "teams",
            "collections", "search", "login", "signup", "wp-login", "privacy", "about", "redeem", "store",
            "payments", "popout", "embed", "moderator");

    private TwitchHandle() {}

    /**
     * The handle in {@code message}, lower-case, if there is one. A URL wins over an @-mention; a bare
     * token counts only with {@code allowBare} and only when the message is nothing but that token.
     */
    public static Optional<String> find(String message, boolean allowBare) {
        if (message == null) return Optional.empty();
        String text = message.strip();
        if (text.isEmpty()) return Optional.empty();
        Matcher url = URL.matcher(text);
        while (url.find()) {
            Optional<String> login = accept(url.group(1));
            if (login.isPresent()) return login;
        }
        Matcher at = AT.matcher(text);
        while (at.find()) {
            Optional<String> login = accept(at.group(1));
            if (login.isPresent()) return login;
        }
        if (allowBare && BARE.matcher(text).matches()) return accept(text);
        return Optional.empty();
    }

    /** Whether {@code login} alone is a usable handle (the shape and not a route). */
    public static Optional<String> accept(String login) {
        if (login == null) return Optional.empty();
        String l = login.strip().toLowerCase(Locale.ROOT);
        if (!BARE.matcher(l).matches() || RESERVED.contains(l)) return Optional.empty();
        return Optional.of(l);
    }
}
