package games.brennan.dungeontrain.client.support;

/**
 * Who a donation made from the Contribute screens is on behalf of: the player themself (the
 * default), or someone else whose Minecraft name they typed.
 *
 * <p>The relay credits a donation to the NAME the checkout carries (Book Boosts, the backer
 * listing: see the relay's {@code bookcredits.js}), so gifting for a friend needs nothing more than
 * sending the friend's name instead of the player's own. {@link PaymentLinks} asks
 * {@link #nameFor} for that name on every link it builds.</p>
 *
 * <p>Client-only and in memory: the Contribute screens {@link #reset()} it each time they open, so a
 * gift never goes to someone else by accident later on.</p>
 */
public final class DonationRecipient {

    /** Minecraft's own name rule: 3 to 16 of letters, digits and underscore. */
    static final int MIN_NAME = 3;
    static final int MAX_NAME = 16;

    private static volatile boolean other;
    private static volatile String typed = "";

    private DonationRecipient() {}

    /** Back to the player themself, name box cleared. */
    public static void reset() {
        other = false;
        typed = "";
    }

    /** Donate as the player themself. */
    public static void chooseSelf() {
        other = false;
    }

    /** Donate on behalf of someone else; the typed name is kept so switching back and forth keeps it. */
    public static void chooseOther() {
        other = true;
    }

    /** The name box's text, as typed. */
    public static void setTyped(String name) {
        typed = name == null ? "" : name.trim();
    }

    public static String typed() {
        return typed;
    }

    /** True while "Someone else" is chosen, whatever the name box holds. */
    public static boolean isOther() {
        return other;
    }

    /** True when the gift goes to a valid name that is not the player's. */
    public static boolean isGifting() {
        return other && isValidName(typed);
    }

    /** True when "Someone else" is chosen but no valid name is typed yet: money links must not open. */
    public static boolean blocked() {
        return other && !isValidName(typed);
    }

    /**
     * The name a donation link should carry: the typed name while gifting, else {@code selfName}.
     * An invalid typed name never leaves the client; callers check {@link #blocked()} first.
     */
    public static String nameFor(String selfName) {
        return nameFor(other, typed, selfName);
    }

    // --- pure logic (unit-tested) ------------------------------------------------------------

    static String nameFor(boolean other, String typed, String selfName) {
        return other && isValidName(typed) ? typed : selfName;
    }

    static boolean isValidName(String name) {
        if (name == null || name.length() < MIN_NAME || name.length() > MAX_NAME) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}
