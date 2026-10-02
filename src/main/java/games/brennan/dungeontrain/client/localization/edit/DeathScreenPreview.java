package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.client.DeathScreenText;
import games.brennan.dungeontrain.narrative.DeathLoreStore;
import games.brennan.dungeontrain.util.SecondPersonDeathMessage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * The death screen ({@code NarrativeDeathScreen}) as the translation preview shows it: one page's
 * kicker (or, on the first page, the death message), train, question, subline, narration and
 * epitaph, drawn with the screen's own text code ({@link DeathScreenText}) in its own layout.
 *
 * <p>Numbers in the lore are filled from a fixed sample run, spelled in the locale being edited, the
 * way the server fills them. The stat tiles and the ride photo are left out: they are not text.</p>
 */
final class DeathScreenPreview {

    /** The pages, in screen order; the train grows one step per page. */
    private static final List<String> PAGES = List.of("fall", "deeds", "gear", "lives", "platform");
    private static final String LORE_BOOK = "death_lore/default";
    private static final String KICKER_KEY = "gui.dungeontrain.death.narr.kicker_";
    private static final String CONTINUE_KEY = "gui.dungeontrain.death.continue";
    private static final String SAMPLE_PLAYER = "Steve";
    private static final String SAMPLE_KILLER = "Zombie";
    /** A plausible run for the lore's {placeholders}. */
    private static final DeathLoreStore.Context SAMPLE_RUN =
        new DeathLoreStore.Context(null, 42, 3, 5, 17, 4, 2, 9, 1234.0, 3, 11);
    private static final int FOOTER_FROM_BOTTOM = 28;

    /**
     * One page of the death screen, its lines filled and ready to draw.
     *
     * @param cause the death message on the first page (second person), or null for the kicker
     */
    record Page(String page, String kicker, String cause, String question, String subline,
                String narration, String epitaph, String continueLabel) {}

    private DeathScreenPreview() {}

    /** The page a death lore line sits on, its entry's other lines beside it. Null if it cannot be placed. */
    static Page forLore(String locale, String field, String typed) {
        int dot = field.indexOf('.');
        String index = field.substring(0, dot);
        String page = pageOf(Integer.parseInt(index));
        if (page == null) {
            return null;
        }
        Map<String, String> fields = new HashMap<>(BookPreviewSiblings.bookFields(locale, LORE_BOOK));
        fields.put(field, typed);
        return page(locale, page, null, fields, index);
    }

    /** The first page, led by a death message ({@code death.attack.*}) as the screen words it. */
    static Page forDeathMessage(String locale, String typed) {
        String filled = BookPreviewContext.fill(typed, SAMPLE_PLAYER, SAMPLE_KILLER);
        String cause = SecondPersonDeathMessage.rewrite(filled, SAMPLE_PLAYER);
        Map<String, String> fields = BookPreviewSiblings.bookFields(locale, LORE_BOOK);
        return page(locale, "fall", cause, fields, firstEntryOn("fall"));
    }

    private static Page page(String locale, String page, String cause, Map<String, String> fields, String index) {
        Map<String, String> lang = BookPreviewSiblings.lang(locale, "gui.dungeontrain.death.");
        return new Page(page, lang.getOrDefault(KICKER_KEY + page, ""), cause,
            line(fields, index, "question", locale), line(fields, index, "subline", locale),
            line(fields, index, "narration", locale), line(fields, index, "epitaph", locale),
            lang.getOrDefault(CONTINUE_KEY, ""));
    }

    private static String line(Map<String, String> fields, String index, String part, String locale) {
        String raw = index == null ? null : fields.get(index + "." + part);
        return raw == null ? "" : DeathLoreStore.substitute(raw, SAMPLE_RUN, locale);
    }

    /**
     * Draws {@code p} filling a {@code width}×{@code height} GUI-pixel screen, as the death screen
     * lays it out; returns the y below its last line of text.
     */
    static int render(GuiGraphics g, Font font, Page p, int width, int height) {
        IntUnaryOperator asIs = c -> c;
        g.fill(0, 0, width, height, DeathScreenText.OVERLAY);
        int w = Math.min(DeathScreenText.MAX_CONTENT_WIDTH, width - 40);
        int cx = width / 2;
        int left = cx - w / 2;
        int y = DeathScreenText.CONTENT_TOP;
        if (p.cause() != null && !p.cause().isEmpty()) {
            y = DeathScreenText.drawCentered(g, font, Component.literal(p.cause()), cx, w, y, DeathScreenText.KICKER) + 3;
        } else {
            DeathScreenText.drawCenteredLine(g, font, Component.literal(p.kicker()), cx, y, DeathScreenText.KICKER);
            y += DeathScreenText.KICKER_STEP;
        }
        int index = PAGES.indexOf(p.page());
        DeathScreenText.drawTrain(g, font, left, w, y, index, PAGES.size(), asIs);
        y += DeathScreenText.TRAIN_STEP;
        y = DeathScreenText.drawQuestion(g, font, p.question(), cx, w, y, DeathScreenText.QUESTION);
        if ("lives".equals(p.page()) && !p.subline().isEmpty()) {
            y = DeathScreenText.drawCentered(g, font, DeathScreenText.styled(p.subline()), cx, w, y,
                DeathScreenText.SUBLINE) + 2;
        }
        y = DeathScreenText.drawNarration(g, font, p.narration(), cx, w, y, DeathScreenText.NARR);
        if ("platform".equals(p.page()) && !p.epitaph().isEmpty()) {
            y = DeathScreenText.drawCentered(g, font, DeathScreenText.styled(p.epitaph()), cx, w, y + 6,
                DeathScreenText.SUBLINE);
        }
        if (!"platform".equals(p.page())) {
            drawContinue(g, font, p.continueLabel(), width, height);
        }
        return y;
    }

    /** How far down {@link #render} would draw text on a {@code width}-wide screen — the same steps, no drawing. */
    static int measure(Font font, Page p, int width) {
        int w = Math.min(DeathScreenText.MAX_CONTENT_WIDTH, width - 40);
        int step = font.lineHeight + 2;
        int y = DeathScreenText.CONTENT_TOP;
        y += p.cause() != null && !p.cause().isEmpty()
            ? lines(font, Component.literal(p.cause()), w) * step + 3
            : DeathScreenText.KICKER_STEP;
        y += DeathScreenText.TRAIN_STEP;
        if (!p.question().isEmpty()) {
            y += 2 + lines(font, DeathScreenText.styled(p.question()), w) * step + 2;
        }
        if ("lives".equals(p.page()) && !p.subline().isEmpty()) {
            y += lines(font, DeathScreenText.styled(p.subline()), w) * step + 2;
        }
        if (!p.narration().isEmpty()) {
            y += 4 + lines(font, DeathScreenText.styled(p.narration()), w) * step;
        }
        if ("platform".equals(p.page()) && !p.epitaph().isEmpty()) {
            y += 6 + lines(font, DeathScreenText.styled(p.epitaph()), w) * step;
        }
        return y;
    }

    private static int lines(Font font, Component text, int w) {
        return font.split(text, w - 8).size();
    }

    /** Whether text ending at {@code textBottom} runs into the footer's Next Screen button. */
    static boolean overlapsFooter(Page p, int textBottom, int height) {
        return !"platform".equals(p.page()) && textBottom > height - FOOTER_FROM_BOTTOM;
    }

    /** The footer's Next Screen button, so text running into it shows. */
    private static void drawContinue(GuiGraphics g, Font font, String label, int width, int height) {
        int bw = 120, h = 18;
        int bx = width / 2 - bw / 2;
        int by = height - FOOTER_FROM_BOTTOM;
        g.fill(bx, by, bx + bw, by + h, 0xFF2A2C33);
        g.renderOutline(bx, by, bw, h, 0xFF4A4C55);
        g.drawCenteredString(font, label, width / 2, by + (h - font.lineHeight) / 2 + 1, 0xFFE8E2D4);
    }

    /** Which page entry {@code index} of the English death lore competes for, or null. */
    private static String pageOf(int index) {
        JsonArray entries = englishEntries();
        if (entries == null || index < 0 || index >= entries.size()) {
            return null;
        }
        JsonElement page = entries.get(index).getAsJsonObject().get("page");
        return page == null ? null : page.getAsString();
    }

    /** The first entry on {@code page} — the sample lore under a death message. */
    private static String firstEntryOn(String page) {
        JsonArray entries = englishEntries();
        for (int i = 0; entries != null && i < entries.size(); i++) {
            JsonElement p = entries.get(i).getAsJsonObject().get("page");
            if (p != null && page.equals(p.getAsString())) {
                return Integer.toString(i);
            }
        }
        return null;
    }

    private static JsonArray englishEntries() {
        String json = ModJarResources.read(TranslationCatalog.englishPathFor(LORE_BOOK));
        try {
            JsonElement root = json == null ? null : JsonParser.parseString(json);
            return root != null && root.isJsonArray() ? root.getAsJsonArray() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
