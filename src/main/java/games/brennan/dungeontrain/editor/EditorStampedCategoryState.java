package games.brennan.dungeontrain.editor;

import java.util.Optional;

/**
 * Server-side memory of which {@link EditorCategory} is currently stamped in
 * the editor world. Set whenever {@code runEnterCategory} stamps a fresh
 * category's plots; cleared whenever {@link EditorCategory#clearAllPlots}
 * tears them down (category switch or {@code /dt editor exit}).
 *
 * <p>Drives {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer}'s
 * label dispatch so the floating name+weight panels persist for as long as the
 * structures themselves are present in the world — not just while the player
 * is standing inside one of the cages.</p>
 *
 * <p>Volatile global because plot stamping happens on the server thread but
 * the snapshot dispatch reads it on every tick from the same thread; the
 * volatile keeps the field cheap and consistent without adding a lock to the
 * tight per-tick path.</p>
 */
public final class EditorStampedCategoryState {

    private static volatile EditorCategory current = null;

    private EditorStampedCategoryState() {}

    /** Mark {@code category} as the actively-stamped editor view. */
    public static void set(EditorCategory category) {
        current = category;
    }

    /** Forget any actively-stamped category — labels should clear. */
    public static void clear() {
        current = null;
    }

    /** The currently stamped category, or empty if no category is active. */
    public static Optional<EditorCategory> current() {
        return Optional.ofNullable(current);
    }
}
