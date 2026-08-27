package games.brennan.dungeontrain.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The section-and-row model behind {@link DungeonTrainClientOptionsScreen} — <b>which</b> settings
 * that screen shows and <b>how they are grouped</b>, with no Minecraft types involved.
 *
 * <p>It is a separate class from the screen for one reason: two of the eleven rows are conditional
 * (Political Filter on Chinese clients, Translate… when a translation target resolves), and the
 * screen packs its rows two-across — so a row appearing or vanishing re-pairs every row after it
 * within its section. That pairing is the part most likely to break silently and the part a
 * headless test can actually reach, which it cannot do through live widgets. Keeping the model
 * plain lets {@code ClientOptionsSectionsTest} pin the grouping directly; the screen is left
 * holding only widget construction.</p>
 *
 * <p>Order here is render order: sections top-to-bottom, rows left-to-right then down.</p>
 */
public final class ClientOptionsSections {

    /** Lang-key prefix for the section headers; the suffix is each {@link Section#titleKey()}. */
    public static final String SECTION_KEY_PREFIX = "gui.dungeontrain.options.section.";

    private ClientOptionsSections() {}

    /** One row of the options screen — identity only; the screen decides what widget renders it. */
    public enum Row {
        EDITOR_SETTINGS,
        TRAIN_VOLUME,
        CONTENT_MODE,
        CUSTOM_CONTENT,
        /** Chinese-language clients only — absent, not merely inert, everywhere else. */
        POLITICAL_FILTER,
        SNAPSHOT_MAX_RES,
        SNAPSHOT_CHAT_LOG,
        BOOK_AUTHOR_CHAT,
        CINEMATIC_HOTKEY,
        INTERNET,
        /** Only when {@code TranslationTarget.resolveForClient()} names a language to edit. */
        TRANSLATE
    }

    /**
     * A titled group of rows. {@code titleKey} is the suffix appended to
     * {@link #SECTION_KEY_PREFIX} to form the header's translation key.
     */
    public record Section(String titleKey, List<Row> rows) {
        public Section {
            rows = List.copyOf(rows);
        }

        /** The full translation key for this section's header. */
        public String fullTitleKey() {
            return SECTION_KEY_PREFIX + titleKey;
        }
    }

    /**
     * The sections to render, in order, for a client with the given two conditional rows.
     *
     * <p>Every section is non-empty in all four combinations — no combination of flags can leave a
     * header stranded above nothing, which is why the conditional rows sit in sections that carry
     * two unconditional rows each.</p>
     */
    public static List<Section> visibleSections(boolean chineseLocale, boolean hasTranslateTarget) {
        List<Section> sections = new ArrayList<>();

        sections.add(new Section("display_audio", List.of(Row.EDITOR_SETTINGS, Row.TRAIN_VOLUME)));

        List<Row> content = new ArrayList<>(List.of(Row.CONTENT_MODE, Row.CUSTOM_CONTENT));
        if (chineseLocale) {
            content.add(Row.POLITICAL_FILTER);
        }
        sections.add(new Section("content", content));

        sections.add(new Section("snapshots", List.of(Row.SNAPSHOT_MAX_RES, Row.SNAPSHOT_CHAT_LOG)));
        sections.add(new Section("chat_hotkeys", List.of(Row.BOOK_AUTHOR_CHAT, Row.CINEMATIC_HOTKEY)));

        List<Row> network = new ArrayList<>(List.of(Row.INTERNET));
        if (hasTranslateTarget) {
            network.add(Row.TRANSLATE);
        }
        sections.add(new Section("network", network));

        return List.copyOf(sections);
    }
}
