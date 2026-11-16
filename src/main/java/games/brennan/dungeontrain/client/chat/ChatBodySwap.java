package games.brennan.dungeontrain.client.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Swaps the words of a printed chat line while keeping what surrounds them: vanilla prints a player's line as
 * {@code <name> words} from a translatable with the words as an argument, and a server or mod can wrap that further.
 */
final class ChatBodySwap {

    private ChatBodySwap() {}

    /** {@code line} with the part reading exactly {@code raw} replaced by {@code body} in that part's style; null when no part does. */
    @Nullable
    static Component withBody(Component line, String raw, Component body) {
        if (line.getString().equals(raw)) return Component.empty().withStyle(line.getStyle()).append(body);
        if (line.getContents() instanceof TranslatableContents t) {
            Object[] args = t.getArgs();
            // Last first: the words come after the name, which may read the same.
            for (int i = args.length - 1; i >= 0; i--) {
                Component arg = args[i] instanceof Component c ? c : args[i] instanceof String s ? Component.literal(s) : null;
                Component swapped = arg == null ? null : withBody(arg, raw, body);
                if (swapped == null) continue;
                Object[] next = args.clone();
                next[i] = swapped;
                return withSiblings(Component.translatableWithFallback(t.getKey(), t.getFallback(), next), line, line.getSiblings());
            }
        }
        List<Component> siblings = line.getSiblings();
        for (int i = 0; i < siblings.size(); i++) {
            Component swapped = withBody(siblings.get(i), raw, body);
            if (swapped == null) continue;
            List<Component> next = new java.util.ArrayList<>(siblings);
            next.set(i, swapped);
            return withSiblings(line.plainCopy(), line, next);
        }
        return null;
    }

    private static Component withSiblings(MutableComponent out, Component like, List<Component> siblings) {
        out.setStyle(like.getStyle());
        siblings.forEach(out::append);
        return out;
    }
}
