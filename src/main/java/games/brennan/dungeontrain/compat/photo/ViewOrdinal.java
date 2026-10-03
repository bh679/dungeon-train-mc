package games.brennan.dungeontrain.compat.photo;

/** Which English ordinal ending a number takes: 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st. */
public final class ViewOrdinal {

    private ViewOrdinal() {}

    /** {@code "st"}, {@code "nd"}, {@code "rd"} or {@code "th"} — also the lang-key suffix for that ending. */
    public static String ending(int n) {
        int lastTwo = Math.abs(n) % 100;
        if (lastTwo >= 11 && lastTwo <= 13) return "th";
        return switch (lastTwo % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }
}
