package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Rebuilds a recorded {@link Component} with one translation key swapped for the typed text, so a
 * preview shows a whole line — or tooltip, or message — exactly as the game built it, with only the
 * string being translated changed.
 *
 * <p>The key's own arguments still fill its placeholders, so a translation that moves a {@code %s}
 * sees its real value land in the new place. Styles (colour, bold, …) and siblings are kept.</p>
 */
final class ComponentSubstitute {

    private static final int MAX_DEPTH = 16;

    private ComponentSubstitute() {}

    /** {@code component} with every use of {@code key} reading {@code typed} instead. */
    static Component replace(Component component, String key, String typed) {
        return replace(component, key, typed, 0);
    }

    /** Whether {@code key} appears anywhere in {@code component}, arguments included. */
    static boolean contains(Component component, String key) {
        return contains(component, key, 0);
    }

    private static MutableComponent replace(Component component, String key, String typed, int depth) {
        if (depth > MAX_DEPTH) {
            return component.copy();
        }
        MutableComponent out;
        if (component.getContents() instanceof TranslatableContents contents) {
            Object[] args = contents.getArgs().clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component nested) {
                    args[i] = replace(nested, key, typed, depth + 1);
                }
            }
            out = key.equals(contents.getKey())
                // Translatable with a fallback and no key the game knows: the typed text is the
                // format, so its placeholders still take this line's real arguments.
                ? Component.translatableWithFallback(key + ".dungeontrain_preview", typed, args)
                : Component.translatableWithFallback(contents.getKey(), contents.getFallback(), args);
        } else {
            out = MutableComponent.create(component.getContents());
        }
        out.setStyle(component.getStyle());
        for (Component sibling : component.getSiblings()) {
            out.append(replace(sibling, key, typed, depth + 1));
        }
        return out;
    }

    private static boolean contains(Component component, String key, int depth) {
        if (component == null || depth > MAX_DEPTH) {
            return false;
        }
        if (component.getContents() instanceof TranslatableContents contents) {
            if (key.equals(contents.getKey())) {
                return true;
            }
            for (Object arg : contents.getArgs()) {
                if (arg instanceof Component nested && contains(nested, key, depth + 1)) {
                    return true;
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            if (contains(sibling, key, depth + 1)) {
                return true;
            }
        }
        return false;
    }
}
