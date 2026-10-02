package games.brennan.dungeontrain.echo;

import java.util.List;

/**
 * Where an echo's credit line goes in an item's lore. Pure — lines are compared as plain strings —
 * so the placement rules are unit-tested without Minecraft.
 *
 * <ul>
 *   <li>Under {@link #MAX_LINES} lines the credit is appended below everything else.</li>
 *   <li>At or over it, the credit replaces a line instead of growing the list: the oldest earlier
 *       echo credit still on the item, or failing that the last line — so the item's own
 *       description (AIN's name lore, "Crafted by …") stays on top the longest.</li>
 * </ul>
 */
public final class EchoCreditLore {

    /** Lore lines an item may carry before a credit replaces one rather than adding a line. */
    public static final int MAX_LINES = 4;

    /** Sentinel from {@link #slotFor}: append a new line. */
    public static final int APPEND = -1;

    private EchoCreditLore() {}

    /**
     * The index the new credit should overwrite, or {@link #APPEND}.
     *
     * @param lines        the item's current lore, top to bottom, as plain text
     * @param priorCredits earlier echo credit lines recorded on the item, oldest first
     */
    public static int slotFor(List<String> lines, List<String> priorCredits) {
        if (lines.size() < MAX_LINES) {
            return APPEND;
        }
        for (String credit : priorCredits) {
            int at = lines.indexOf(credit);
            if (at >= 0) {
                return at;
            }
        }
        return lines.size() - 1;
    }
}
