package games.brennan.dungeontrain.client.policy;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The carded sections of {@link AiPolicyScreen}, as data — which headings exist, what colour each
 * one is accented, and which bullets sit under it with which item glyph.
 *
 * <p>Split out so the screen is about <em>layout</em> and this file is about <em>content</em>.
 * Adding or reordering a bullet is an edit here and nothing else; the screen lays out whatever it
 * is handed.</p>
 *
 * <p>Glyphs are {@link Items} constants rather than ids resolved at runtime, so a wrong one is a
 * compile error rather than a silently-missing icon on a page most players see once. That is also
 * why there is no unit test for this file: there is no logic to test, and the compiler already
 * checks the only thing that could be wrong.</p>
 *
 * <p>The intro and closing paragraphs are deliberately NOT here — they frame the page rather than
 * being sections of it, and the screen draws them as plain un-carded text.</p>
 */
public final class AiPolicyContent {

    /** Reassuring green — the list of things that are made by hand. */
    public static final int ACCENT_NOT_AI = 0xFF5FBF5F;
    /** The death-screen amber (see {@code DonationOptionsScreen}) — the honest-disclosure list. */
    public static final int ACCENT_USED = 0xFFE0B56A;
    /** The page's own link blue, for the closing "which AI" note. */
    public static final int ACCENT_WHICH = 0xFF5B9BFF;

    private AiPolicyContent() {}

    /**
     * One bullet: its translation key and the item drawn beside it. {@code glyph} is null for a
     * section that is a paragraph rather than a list — see {@link Section#body()}.
     */
    public record Bullet(String key, Item glyph) {}

    /**
     * One card. Either a bullet list ({@code bullets} non-empty) or a single wrapped paragraph
     * ({@code body} non-null) — never both.
     */
    public record Section(String headerKey, int accent, List<Bullet> bullets, String body) {

        static Section list(String headerKey, int accent, List<Bullet> bullets) {
            return new Section(headerKey, accent, List.copyOf(bullets), null);
        }

        static Section paragraph(String headerKey, int accent, String body) {
            return new Section(headerKey, accent, List.of(), body);
        }

        public boolean isParagraph() {
            return body != null;
        }
    }

    private static final List<Section> SECTIONS = List.of(
            // The part a sceptical player came for, so it leads.
            Section.list("gui.dungeontrain.ai_policy.not_ai.header", ACCENT_NOT_AI, List.of(
                    new Bullet("gui.dungeontrain.ai_policy.not_ai.1", Items.WRITTEN_BOOK),
                    new Bullet("gui.dungeontrain.ai_policy.not_ai.2", Items.OAK_SIGN),
                    new Bullet("gui.dungeontrain.ai_policy.not_ai.3", Items.PLAYER_HEAD),
                    // A barrier for the flat "no": nothing here is trained on your data.
                    new Bullet("gui.dungeontrain.ai_policy.not_ai.4", Items.BARRIER),
                    new Bullet("gui.dungeontrain.ai_policy.not_ai.5", Items.PAINTING),
                    new Bullet("gui.dungeontrain.ai_policy.not_ai.6", Items.NAME_TAG),
                    new Bullet("gui.dungeontrain.ai_policy.not_ai.7", Items.WRITABLE_BOOK),
                    new Bullet("gui.dungeontrain.ai_policy.not_ai.8", Items.COMPARATOR))),

            Section.list("gui.dungeontrain.ai_policy.used.header", ACCENT_USED, List.of(
                    // A hopper filters — which is exactly what the book review does.
                    new Bullet("gui.dungeontrain.ai_policy.used.1", Items.HOPPER),
                    new Bullet("gui.dungeontrain.ai_policy.used.2", Items.ENCHANTED_BOOK),
                    new Bullet("gui.dungeontrain.ai_policy.used.3", Items.PAPER),
                    new Bullet("gui.dungeontrain.ai_policy.used.4", Items.CRAFTING_TABLE),
                    new Bullet("gui.dungeontrain.ai_policy.used.5", Items.REDSTONE_TORCH))),

            Section.paragraph("gui.dungeontrain.ai_policy.which.header", ACCENT_WHICH,
                    "gui.dungeontrain.ai_policy.which.body"));

    /** The cards, in render order. */
    public static List<Section> sections() {
        return SECTIONS;
    }
}
