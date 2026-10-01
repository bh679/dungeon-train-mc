package games.brennan.dungeontrain.client.localization.edit;

/**
 * What is known for certain about where a lang key is read — the facts
 * {@link TranslationPreviewKind#viewsOf} picks the preview's views from.
 *
 * <p>Each answer is either fixed by the code and data (an item's registered name, an advancement's
 * own file) or something this install has seen the game do (a button label, a chat line). The
 * interface keeps the rules pure and testable; {@link ClientPreviewEvidence} answers in game.</p>
 */
public interface PreviewEvidence {

    /** Seen labelling a real button. */
    default boolean seenOnButton(String key) { return false; }

    /** Seen in a chat message. */
    default boolean seenInChat(String key) { return false; }

    /** Seen in an action-bar message. */
    default boolean seenInActionBar(String key) { return false; }

    /** Seen in a widget's hover tooltip, on a screen that was recorded. */
    default boolean seenInWidgetTooltip(String key) { return false; }

    /** The registered name of an item or block that has an item. */
    default boolean isItemName(String key) { return false; }

    /** Seen in an item's tooltip. */
    default boolean seenInItemTooltip(String key) { return false; }

    /** An advancement title that pops up as the vanilla toast when earned. */
    default boolean showsAdvancementToast(String key) { return false; }

    /** Knows nothing; every answer is no. */
    PreviewEvidence NONE = new PreviewEvidence() { };
}
