package games.brennan.dungeontrain.client.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every {@code MenuLang.t("…")} literal in {@code client/menu} resolves to a key in
 * {@code en_us.json}, and every {@code editor_menu.*} key in the file is reached by one. A menu
 * label that misses its key renders as the key itself, in-game only, on a creative-only screen —
 * the kind of thing nobody notices until a translator does.
 */
@ExtendWith(MenuTestLanguage.class)
class MenuLangKeysTest {

    private static final Path MENU = Path.of("src/main/java/games/brennan/dungeontrain/client/menu");
    private static final Path LANG = Path.of("src/main/resources/assets/dungeontrain/lang/en_us.json");

    /** {@code MenuLang.t("suffix"} — the literal-suffix form; ternaries inside t(...) are matched too. */
    private static final Pattern T_CALL = Pattern.compile("MenuLang\\.t\\(\\s*\"([^\"]+)\"\\s*[,)]");
    /** {@code MenuLang.PREFIX + "suffix"} — the Component.translatable form. */
    private static final Pattern PREFIX_CONCAT = Pattern.compile("MenuLang\\.PREFIX\\s*\\+\\s*\"([^\"]+)\"");
    private static final Pattern T_TERNARY = Pattern.compile(
        "MenuLang\\.t\\([^\",;]*?\\?\\s*\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
    /** {@code MenuLang.t("prefix." + key)} — the enum-suffix form; those keys are checked by name below. */
    private static final Pattern T_DYNAMIC = Pattern.compile("MenuLang\\.t\\(\\s*\"([^\"]+\\.)\"\\s*\\+");
    private static final Pattern NAMED_CALL = Pattern.compile("MenuLang\\.named\\(\\s*\"([^\"]+)\"");
    /** {@code MenuLang.plural("base", n)} — resolves {@code base.<category>}; en_us carries one + other. */
    private static final Pattern PLURAL_CALL = Pattern.compile("MenuLang\\.plural\\(\\s*\"([^\"]+)\"");

    private static JsonObject english() throws IOException {
        return JsonParser.parseString(Files.readString(RepoPaths.root().resolve(LANG),
            StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(RepoPaths.root().resolve(MENU))) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("Every literal MenuLang.t(...) suffix has an en_us line")
    void everyLiteralSuffixExists() throws IOException {
        JsonObject en = english();
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Path p : sources()) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            for (Pattern pat : List.of(T_CALL, T_TERNARY, PREFIX_CONCAT)) {
                Matcher m = pat.matcher(src);
                while (m.find()) {
                    for (int g = 1; g <= m.groupCount(); g++) {
                        String key = MenuLang.PREFIX + m.group(g);
                        checked++;
                        if (!en.has(key)) {
                            missing.add(p.getFileName() + ": " + key);
                        }
                    }
                }
            }
            Matcher pl = PLURAL_CALL.matcher(src);
            while (pl.find()) {
                for (String form : List.of("one", "other")) {
                    String key = MenuLang.PREFIX + pl.group(1) + "." + form;
                    checked++;
                    if (!en.has(key)) {
                        missing.add(p.getFileName() + ": " + key);
                    }
                }
            }
        }
        assertTrue(checked > 300, "expected the sweep to find the menu labels, found " + checked);
        assertEquals(List.of(), missing);
    }

    @Test
    @DisplayName("Every editor_menu.* key in en_us is reachable from a MenuLang call")
    void everyKeyIsReachable() throws IOException {
        JsonObject en = english();
        TreeSet<String> literal = new TreeSet<>();
        TreeSet<String> dynamicPrefixes = new TreeSet<>();
        for (Path p : sources()) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            for (Pattern pat : List.of(T_CALL, T_TERNARY, PREFIX_CONCAT)) {
                Matcher m = pat.matcher(src);
                while (m.find()) {
                    for (int g = 1; g <= m.groupCount(); g++) {
                        literal.add(MenuLang.PREFIX + m.group(g));
                    }
                }
            }
            Matcher d = T_DYNAMIC.matcher(src);
            while (d.find()) {
                dynamicPrefixes.add(MenuLang.PREFIX + d.group(1));
            }
            Matcher n = NAMED_CALL.matcher(src);
            while (n.find()) {
                dynamicPrefixes.add(MenuLang.PREFIX + n.group(1) + ".");
            }
            Matcher pl = PLURAL_CALL.matcher(src);
            while (pl.find()) {
                dynamicPrefixes.add(MenuLang.PREFIX + pl.group(1) + ".");
            }
        }
        // MenuLang.typeName builds "type_name.<slug>" from what the server pushed.
        dynamicPrefixes.add(MenuLang.PREFIX + "type_name.");
        List<String> unreachable = new ArrayList<>();
        for (String key : en.keySet()) {
            if (!key.startsWith(MenuLang.PREFIX) || literal.contains(key)) {
                continue;
            }
            if (dynamicPrefixes.stream().noneMatch(key::startsWith)) {
                unreachable.add(key);
            }
        }
        assertEquals(List.of(), unreachable);
    }
}
