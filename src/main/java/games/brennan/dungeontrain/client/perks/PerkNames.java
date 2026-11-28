package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Perk and class names in the reader's language. {@code tree.json} holds the English; every name has a
 * lang key ({@code perk.dungeontrain.<id>.name}, {@code perk.dungeontrain.class.<id>}), and the English
 * is the fallback for a key that is missing.
 */
@OnlyIn(Dist.CLIENT)
public final class PerkNames {

    private PerkNames() {}

    /** A perk's name. The root has no key and keeps its own. */
    public static String perk(PerkTree.Perk p) {
        return p.isRoot() ? p.name() : translated("perk.dungeontrain." + p.id() + ".name", p.name());
    }

    /** A class's name. */
    public static String cls(PerkTree.PerkClass c) {
        return translated("perk.dungeontrain.class." + c.id(), c.name());
    }

    /**
     * Perk text as written: it takes no arguments, so it is read straight from the language, never
     * formatted ({@code I18n.get} would read a "%" in "X% slower" as a format code and print a
     * format error).
     */
    static String translated(String key, String fallback) {
        return I18n.exists(key) ? Language.getInstance().getOrDefault(key) : fallback;
    }
}
