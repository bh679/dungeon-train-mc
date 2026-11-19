package games.brennan.dungeontrain.client;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * One-time secrets for {@link games.brennan.dungeontrain.chat.ChatClick} buttons that act for the player.
 *
 * <p>{@code ScreenChatClickMixin} runs every {@code dungeontrain:} click, whoever wrote the line: a server or a book
 * can print one too. A button whose click does something the player can't take back mints a token when this client
 * prints it and puts the token in its click value; the handler {@link #consume}s it. Another sender can't know the
 * token, so its copy of the button does nothing.</p>
 *
 * <p>A token works once, for {@link #TTL_MS} unless its button asks for longer, and not in its first {@link #MIN_AGE_MS}: a double click on a line that
 * is replaced under the cursor by the real one must not land on the real one. Client thread only.</p>
 */
public final class ChatClickTokens {

    public static final long TTL_MS = 2 * 60 * 1000;
    public static final long MIN_AGE_MS = 500;
    static final int MAX = 256;
    private static final int BYTES = 16;

    private record Minted(String token, long atMs, long ttlMs) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Minted> TOKENS = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Minted> eldest) {
            return size() > MAX;
        }
    };
    private static LongSupplier clock = System::currentTimeMillis;

    private ChatClickTokens() {}

    /** A new token for button {@code id} of {@code scope}, replacing any earlier one. Fits a click segment. */
    public static String mint(String scope, long id) {
        return mint(scope, id, TTL_MS);
    }

    /**
     * {@link #mint(String, long)} for a button that stays clickable {@code ttlMs} instead of {@link #TTL_MS}: one the
     * player may come back to, such as a log request the server waits ten minutes on.
     */
    public static synchronized String mint(String scope, long id, long ttlMs) {
        if (ttlMs <= MIN_AGE_MS) throw new IllegalArgumentException("a chat click token must outlive its first " + MIN_AGE_MS + " ms");
        byte[] b = new byte[BYTES];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String k = key(scope, id);
        TOKENS.remove(k); // re-insert at the end, so the bound drops the oldest
        TOKENS.put(k, new Minted(token, clock.getAsLong(), ttlMs));
        return token;
    }

    /** Whether {@code token} is the live one for {@code id}. Uses it up either way, so a guess costs the real one. */
    public static synchronized boolean consume(String scope, long id, @Nullable String token) {
        Minted m = TOKENS.remove(key(scope, id));
        if (m == null || token == null) return false;
        long age = clock.getAsLong() - m.atMs();
        if (age < MIN_AGE_MS || age > m.ttlMs()) return false;
        return MessageDigest.isEqual(m.token().getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII));
    }

    /** Forget every token of {@code scope}: its buttons mean nothing now. */
    public static synchronized void clear(String scope) {
        String prefix = scope + "\n";
        TOKENS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String key(String scope, long id) {
        return scope + "\n" + id;
    }

    /** Test seam: read time from {@code c}; null restores the wall clock. Also forgets every token. */
    public static synchronized void useClock(@Nullable LongSupplier c) {
        clock = c != null ? c : System::currentTimeMillis;
        TOKENS.clear();
    }

    static synchronized int size() {
        return TOKENS.size();
    }
}
