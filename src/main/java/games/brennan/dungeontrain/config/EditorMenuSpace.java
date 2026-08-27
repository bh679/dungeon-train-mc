package games.brennan.dungeontrain.config;

/**
 * Where one of the editor's menus draws itself, and therefore how it is pointed at.
 *
 * <p>The two modes are not merely a skin — they are different input models, which is why this
 * is a player-facing choice rather than a constant:</p>
 *
 * <ul>
 *   <li>{@link #WORLDSPACE} — the panel is a flat quad anchored in the world and cells are
 *       picked by a camera raycast, so the author aims their head at a row. The cursor stays
 *       captured, so they can keep walking while the panel is up.</li>
 *   <li>{@link #SCREENSPACE} — the panel is a real {@code Screen} drawn in GUI pixels with a
 *       free mouse cursor. Pointing is precise, but the cursor is released and movement stops.</li>
 * </ul>
 *
 * <p>Both modes share one layout. Every editor panel is laid out in <em>panel-local</em> units
 * (x right, y up, origin at the panel centre); a mode only decides the outer transform used to
 * draw those units and where the {@code (x, y)} probe that hit-tests them comes from. That is
 * why hover and click cannot drift apart between the two — they run the same hit-test.</p>
 *
 * <p>Stored per-menu in {@link ClientDisplayConfig} rather than as one global switch, and with
 * per-menu <em>defaults</em> — see the {@code DEFAULT_*_MENU_SPACE} constants there. The menus
 * split cleanly in two: X and V act on the whole plot, while C and Z act on one block cell and
 * carry its position, so their world-space panel appears beside the very block being edited.
 * That anchoring is information a screen-space panel cannot show, which is why the two groups
 * ship pointing different ways. There is deliberately no single {@code DEFAULT} here — a shared
 * one would make it easy to hand a menu the wrong group's answer.</p>
 */
public enum EditorMenuSpace {

    /** A flat quad anchored in the world, picked by aiming the camera. */
    WORLDSPACE,

    /** A GUI {@code Screen} in pixels, picked with a free mouse cursor. */
    SCREENSPACE;

    public boolean isScreenspace() {
        return this == SCREENSPACE;
    }

    public boolean isWorldspace() {
        return this == WORLDSPACE;
    }

    /** The other mode — what a toggle row switches to. */
    public EditorMenuSpace toggled() {
        return this == SCREENSPACE ? WORLDSPACE : SCREENSPACE;
    }
}
