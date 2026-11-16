package games.brennan.dungeontrain.moderation;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The pure rules behind {@code /livereport}: which live stream a player is reporting, the preset reasons,
 * and how long ago an open report was made. Nothing here touches the server or the relay, so the tests pin
 * it all.
 */
public final class LiveReport {

    /** Longest reason sent; the relay keeps the same. Also keeps a button's command under chat's 256. */
    public static final int MAX_REASON = 200;

    /** The picker's reasons, sent as these codes. Typed text is sent as typed. */
    public static final List<String> PRESET_REASONS = List.of("hate_speech", "harassment", "sexual_content", "other");

    /** One live stream: which of the pair it is (1 or 2), its relay slot, who streams it (uuid when known). */
    public record Stream(int index, String channel, String name, @Nullable UUID uuid) {}

    /** What {@link #resolve} found. {@code streams} holds the one target, or the ones to pick from. */
    public record Target(Kind kind, List<Stream> streams) {
        public enum Kind { NONE, SELF_ONLY, ONE, PICK }

        public Stream only() {
            return streams.get(0);
        }
    }

    private LiveReport() {}

    /**
     * The streams {@code reporter} may report: every live one except their own (by uuid, else by name,
     * ignoring case). NONE when nothing is live, SELF_ONLY when the only stream is theirs.
     */
    public static Target resolve(List<Stream> live, @Nullable UUID reporter, String reporterName) {
        List<Stream> others = new ArrayList<>();
        for (Stream s : live) {
            if (!isSelf(s, reporter, reporterName)) others.add(s);
        }
        if (others.isEmpty()) {
            return new Target(live.isEmpty() ? Target.Kind.NONE : Target.Kind.SELF_ONLY, List.of());
        }
        return new Target(others.size() == 1 ? Target.Kind.ONE : Target.Kind.PICK, List.copyOf(others));
    }

    /** The stream numbered {@code index} among {@code live}, unless it is the reporter's own; null otherwise. */
    @Nullable
    public static Stream byIndex(List<Stream> live, int index, @Nullable UUID reporter, String reporterName) {
        for (Stream s : live) {
            if (s.index() == index && !isSelf(s, reporter, reporterName)) return s;
        }
        return null;
    }

    static boolean isSelf(Stream s, @Nullable UUID reporter, String reporterName) {
        if (s.uuid() != null && reporter != null) return s.uuid().equals(reporter);
        return s.name() != null && !s.name().isEmpty() && s.name().equalsIgnoreCase(reporterName);
    }

    /** A reason as sent: a preset code (any case) as its code, otherwise the text trimmed and capped. */
    public static String reason(@Nullable String typed) {
        if (typed == null) return "";
        String s = typed.strip();
        String code = s.toLowerCase(Locale.ROOT);
        if (PRESET_REASONS.contains(code)) return code;
        return s.length() > MAX_REASON ? s.substring(0, MAX_REASON) : s;
    }

    /** How long ago, as a lang key suffix and its number: {@code just_now}, {@code minutes}, {@code hours}, {@code days}. */
    public record Ago(String unit, long amount) {}

    /** {@code thenMs} relative to {@code nowMs}. Unknown (0 or future) reads as just now. */
    public static Ago ago(long thenMs, long nowMs) {
        long sec = thenMs <= 0 || nowMs <= thenMs ? 0 : (nowMs - thenMs) / 1000;
        if (sec < 60) return new Ago("just_now", 0);
        if (sec < 3600) return new Ago("minutes", sec / 60);
        if (sec < 86400) return new Ago("hours", sec / 3600);
        return new Ago("days", sec / 86400);
    }
}
