package games.brennan.dungeontrain.naming;

import java.util.Locale;

/**
 * Quality bucket selected by the composer when rolling a name. Plain items
 * draw from the short chain; enchanted (or otherwise "special") items draw
 * from the elaborated chain so name length reads as rarity.
 *
 * <p>Stored as a lowercase string key inside {@link NameSelector#tiers()};
 * the enum exists so callers can't fat-finger the key.</p>
 */
public enum NameTier {
    PLAIN,
    ENCHANTED;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
