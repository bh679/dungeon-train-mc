package games.brennan.dungeontrain.client.menu.editorscreen;

/**
 * Which layer of the X menu takes an input, in one place.
 *
 * <p>The screen stacks a modal host, the creator search panel, an inline number field, the filter
 * box, a stage family's dropdown and the panes. Which of them answers a click, a wheel notch, a key
 * or a typed character is a fixed order that is easy to get subtly wrong — a click must abandon a
 * half-typed value, a typed character must reach the search panel before the inline field, Escape
 * must close a dropdown before it pops a modal. Pure, so {@code ScreenInputGateTest} pins it.</p>
 */
final class ScreenInputGate {

    /** The layers, from the one drawn on top down to the panes. */
    enum Layer { MODAL, SEARCH, INLINE_EDIT, FILTER_BOX, STAGE_DROPDOWN, PANES }

    /**
     * What is up right now.
     *
     * @param modalOpen         the modal host shows a screen
     * @param modalTyping       the modal host is capturing a typed name (with or without a screen)
     * @param searchOpen        the creator search panel is open
     * @param inlineEditActive  a number is being typed over a cell
     * @param filterBoxFocused  the filter box has keyboard focus and is shown
     * @param stageDropdownOpen a stage family's dropdown is open (Stages tab only)
     */
    record State(boolean modalOpen, boolean modalTyping, boolean searchOpen, boolean inlineEditActive,
                 boolean filterBoxFocused, boolean stageDropdownOpen) {
        static final State NONE = new State(false, false, false, false, false, false);
    }

    private ScreenInputGate() {}

    /** A click: the modal, else the search panel, else an open dropdown takes it; else the panes. */
    static Layer click(State s) {
        if (s.modalOpen()) return Layer.MODAL;
        if (s.searchOpen()) return Layer.SEARCH;
        if (s.stageDropdownOpen()) return Layer.STAGE_DROPDOWN;
        return Layer.PANES;
    }

    /**
     * A click past the modal and the search panel abandons a half-typed value rather than leaving it
     * hanging over a cell whose row may be about to change underneath it.
     */
    static boolean clickCancelsInlineEdit(State s) {
        return !s.modalOpen() && !s.searchOpen() && s.inlineEditActive();
    }

    /** A wheel notch: the modal, else the search panel; else the panes (the dropdown is one of them). */
    static Layer scroll(State s) {
        if (s.modalOpen()) return Layer.MODAL;
        if (s.searchOpen()) return Layer.SEARCH;
        return Layer.PANES;
    }

    /**
     * A key press: whatever is capturing text takes it first — the inline field, then the search
     * panel, then a typing modal, then the focused filter box — else an open dropdown (Escape
     * closes it before anything pops), else the panes and the screen's own bindings.
     */
    static Layer key(State s) {
        if (s.inlineEditActive()) return Layer.INLINE_EDIT;
        if (s.searchOpen()) return Layer.SEARCH;
        if (s.modalTyping()) return Layer.MODAL;
        if (s.filterBoxFocused()) return Layer.FILTER_BOX;
        if (s.stageDropdownOpen()) return Layer.STAGE_DROPDOWN;
        return Layer.PANES;
    }

    /**
     * A typed character: the search panel before the inline field — a search is opened from a
     * chip while no number is being typed, and its text field must not lose a character to a stale
     * inline edit — then a typing modal; else the screen's own handling.
     */
    static Layer charTyped(State s) {
        if (s.searchOpen()) return Layer.SEARCH;
        if (s.inlineEditActive()) return Layer.INLINE_EDIT;
        if (s.modalTyping()) return Layer.MODAL;
        return Layer.PANES;
    }
}
