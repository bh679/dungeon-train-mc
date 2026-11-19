package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.chat.ChatClick;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Test helper: the DT chat click values on a printed line, as their segments. */
public final class ChatClickValues {

    private ChatClickValues() {}

    /** Each distinct {@code dungeontrain:} click on {@code line} and its siblings, in order, as segments (action first). */
    public static List<List<String>> of(Component line) {
        Set<String> values = new LinkedHashSet<>();
        walk(line, values);
        List<List<String>> out = new ArrayList<>();
        for (String v : values) {
            List<String> parts = ChatClick.parse(v);
            if (parts != null) out.add(parts);
        }
        return out;
    }

    /** Every click in {@code c}: its own, its translation arguments' (unresolved in tests), its siblings'. */
    private static void walk(Component c, Set<String> values) {
        ClickEvent e = c.getStyle().getClickEvent();
        if (e != null) values.add(e.getValue());
        if (c.getContents() instanceof TranslatableContents t) {
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component a) walk(a, values);
            }
        }
        for (Component sibling : c.getSiblings()) walk(sibling, values);
    }

    /** The one click on {@code line} whose step (the segment after the action) is {@code step}. */
    public static List<String> step(Component line, String step) {
        for (List<String> parts : of(line)) {
            if (parts.size() > 1 && parts.get(1).equals(step)) return parts;
        }
        throw new AssertionError("no " + step + " click on " + line.getString());
    }
}
